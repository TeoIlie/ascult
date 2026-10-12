# Technical Design

## Overview

A single-screen Android app with no backend. Microphone audio is streamed to the Soniox real-time WebSocket API, which returns the Romanian transcript and its English translation as a token stream. Both are shown live on screen.

```text
Mic ──► AudioRecord (16 kHz mono PCM16) ──► OkHttp WebSocket ──► Soniox stt-rt
                                                                   │
Compose UI ◄── ViewModel (StateFlow) ◄── token stream (Romanian + English,
                                          final + provisional)
```

## Components

| Concern | Choice | Notes |
| --- | --- | --- |
| UI | Jetpack Compose, single Activity | Two panes: Romanian (top), English (bottom). Provisional text dimmed. |
| State | `ViewModel` + `StateFlow`, coroutines | UI observes state; service owns the session. |
| Audio | `AudioRecord`, 16 kHz, mono, `pcm_s16le` | Selectable audio source, software gain, level meter. |
| Network | OkHttp `WebSocket` | No Soniox Android SDK; the protocol is small. |
| Background | Foreground service, type `microphone` | Keeps listening with the screen off. Must be started while the app is visible (Android 14+). |
| Settings | Jetpack DataStore | API key, audio source, gain, dictionary. |
| Distribution | `adb install` / Android Studio Run | GrapheneOS, no Play Services. |

## Soniox protocol

- Endpoint: `wss://stt-rt.soniox.com/transcribe-websocket`
- First message (JSON text frame):

  ```json
  {
    "api_key": "<key>",
    "model": "stt-rt-v5",
    "audio_format": "pcm_s16le",
    "sample_rate": 16000,
    "num_channels": 1,
    "language_hints": ["ro"],
    "context": { "...": "see Dictionary" },
    "translation": { "type": "one_way", "target_language": "en" }
  }
  ```

- Then binary frames of raw PCM audio.
- Responses carry `tokens[]` with `text`, `is_final`, `language`, and `translation_status` (distinguishes original from translated tokens). Final tokens are appended once; non-final tokens replace the previous provisional tail.
- Send a keepalive message at least every 40 s when no audio is flowing. The app streams audio continuously while listening and has no pause, so it does not need keepalives.
- End the stream with an empty text frame.

Verify field names against the current Soniox docs when implementing; the model name changes between versions.

## Dictionary (custom vocabulary)

The speaker uses uncommon words, so the session config carries a default dictionary via Soniox's `context` object:

- `terms`: Romanian words/names the recogniser should expect.
- `translation_terms`: `{ "source": "...", "target": "..." }` pairs forcing a specific English rendering.
- `general` / `text`: optional domain and background description (e.g. topic, setting).

The default ships as a bundled asset (`app/src/main/assets/dictionary.json`), version-controlled, and shared with `tools/stream_test.py`. Settings → Dictionary edits it in the app as one-entry-per-line text (expected words, `source = target` translations, `key: value` context, background text). Edits are stored as an override in DataStore; Reset returns to the bundled default, and Share JSON exports the edited dictionary in the asset's format so it can be copied back into the repo.

## Audio tuning

The **Audio** button opens a sheet with three settings, stored in DataStore:

- **Microphone source**: `VOICE_RECOGNITION` (default), `UNPROCESSED`, `MIC`, `CAMCORDER`. Each applies different device processing; the best one for a distant speaker has to be found by ear and by transcript quality.
- **Gain**: 0 to +24 dB software gain with clipping, applied live. It raises quiet input but does not improve signal-to-noise.
- **Save recording**: writes exactly what is sent to Soniox (after gain) as a 16 kHz mono WAV named `<time>_<SOURCE>_+<gain>dB.wav`.

Recordings land in the app's external files directory. To compare them on the desktop:

```bash
adb pull /sdcard/Android/data/com.teoilie.ascult/files/recordings/ recordings/
tools/.venv/bin/python tools/stream_test.py recordings/<file>.wav --speed 0
```

## Sessions, reconnection and transcripts

- Each Start appends a segment labelled with its source, gain and start time; text is kept across Stop/Start until Clear.
- Captured audio goes into a 30 s buffer that outlives individual WebSocket connections. If the connection drops (network error, or Soniox timeout/rate-limit/server error), the app reconnects with backoff (1, 2, 4, 8, then 10 s) and sends the buffered audio, so only that connection's provisional text is lost. Other Soniox errors (e.g. invalid key) end the session.
- Share sends the transcript as plain text. Each conversation (since the last Clear) is also saved when a session ends, to `/sdcard/Android/data/com.teoilie.ascult/files/transcripts/<start time>.txt`.

## API key

Entered in Settings and stored in DataStore (app-private, unencrypted; backups disabled). When it is empty, debug builds fall back to `SONIOX_API_KEY` from the gitignored `.env` at the repo root (the same file `tools/stream_test.py` uses), baked into `BuildConfig`; release builds have no fallback.

A public version would replace this with Soniox temporary keys minted by a small backend; the key is read in one place (`TranslationSession.start`).

## Build steps

Each step ends with something runnable.

1. **Desktop validation.** A Python script (`tools/stream_test.py`) streams the Romanian recording to Soniox with the same config and dictionary the app will use, printing Romanian and English. Confirms the key, protocol, and dictionary effect before any Android work.
2. **Project skeleton.** Android Studio "Empty Activity" (Compose), minSdk 31+, running on the Pixel over USB debugging. Buildable from CLI with `./gradlew installDebug`.
3. **Microphone + level meter.** `RECORD_AUDIO` runtime permission, `AudioRecord` capture loop, live level bar.
4. **Streaming.** WebSocket client, config message with dictionary, audio upload, token parsing, two-pane Romanian/English display with dimmed provisional text. First useful milestone.
5. **Foreground service.** Move capture and streaming into a microphone foreground service with a notification and stop action; screen-off listening.
6. **Audio tuning.** Audio source picker (`UNPROCESSED`, `VOICE_RECOGNITION`, `MIC`, ...), software gain, optional WAV recording of sessions for comparison and replay through the step-1 script.
7. **Polish.** Settings screen (API key, in-app dictionary editing), reconnect on network drop, transcript share and auto-save.

## GrapheneOS notes

- The per-app **Network** permission must be enabled (Settings → Apps → Ascult → Permissions). New apps may install with it denied; the app then shows `Unable to resolve host "stt-rt.soniox.com"`.
- Microphone access may also be gated by the global mic toggle.
