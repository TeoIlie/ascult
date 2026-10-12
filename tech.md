# Technical Design

## Overview

A single-screen Android app with no backend. Microphone audio is streamed to the Soniox real-time WebSocket API, which returns the Romanian transcript and its English translation as a token stream. Both are shown live on screen.

```text
Mic ──► AudioCapture (16 kHz mono PCM16) ──► TranslationSession ──► SonioxClient (WebSocket) ──► Soniox stt-rt
                                                  │  ▲                                                  │
                                                  │  └──────── tokens (Romanian + English, final + provisional)
                                                  ▼
                       SessionState (phase, level, segments) ──► ViewModels ──► Compose UI (SyncedPanes)
```

`TranslationService` (a microphone foreground service) only keeps the process alive while the session listens; the session itself lives in `AscultApp`.

## Components

| Concern | Choice | Notes |
| --- | --- | --- |
| UI | Jetpack Compose, single Activity | Two panes: Romanian (top), English (bottom). Provisional text dimmed. |
| State | Process-wide `TranslationSession` + `StateFlow`, coroutines | ViewModels expose it to the UI; the foreground service keeps it alive. |
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

- While listening with the app visible, the screen is kept from timing out (the power button still turns it off; listening continues). A− / A+ scale both panes' text, stored in DataStore.
- `SessionState.phase` is one of IDLE, LISTENING, RECONNECTING (still capturing into the buffer) or STOPPING (microphone off, waiting for Soniox to finalise). Reconnecting is shown as a neutral status; `error` is only for real failures.
- Each Start appends a segment labelled with its source, gain and start time; text is kept across Stop/Start until Clear.
- Captured audio goes into a 30 s buffer that outlives individual WebSocket connections. If the connection drops (network error, a missed WebSocket pong (pings every 10 s catch silently stalled networks), or a Soniox timeout/rate-limit/server error), the app reconnects with backoff (1, 2, 4, 8, then 10 s) and sends the buffered audio, so only that connection's provisional text is lost. Other Soniox errors (e.g. invalid key) end the session.
- Share sends the transcript as plain text. Each conversation (since the last Clear) is also saved every 15 s while listening and when a session ends, to `/sdcard/Android/data/com.teoilie.ascult/files/transcripts/<start time>.txt`.

## API key

Entered in Settings and stored in DataStore (app-private, unencrypted; backups disabled). When it is empty, debug builds fall back to `SONIOX_API_KEY` from the gitignored `.env` at the repo root (the same file `tools/stream_test.py` uses), baked into `BuildConfig`; release builds have no fallback.

A public version would replace this with Soniox temporary keys minted by a small backend; the key is read in one place (`TranslationSession.start`).

## Code map

Kotlin sources live in `app/src/main/java/com/teoilie/ascult/`.

| Path | Responsibility |
| --- | --- |
| `AscultApp.kt` | Application; holds the shared `SettingsStore` and `TranslationSession` (`context.app.…`). |
| `MainActivity.kt` | Single activity; switches between the translation and settings screens. |
| `audio/AudioCapture.kt` | `AudioRecord` → flow of 100 ms PCM16 frames; level (dBFS) and gain maths. |
| `audio/WavWriter.kt` | WAV file writer; `recordTo` tees frames into a recording. |
| `soniox/SonioxClient.kt` | WebSocket protocol: config message, audio frames, token parsing, errors. Mirrored by `tools/stream_test.py`. |
| `soniox/Transcript.kt` | `Transcript` (final + provisional text), `Segment` (one Start→Stop run), pure update helpers, plain-text export. |
| `soniox/Dictionary.kt` | Soniox `context` model; loads the bundled `assets/dictionary.json`. |
| `session/TranslationSession.kt` | Session lifecycle: start checks, capture → buffer → (re)connect loop, autosave, stop, clear. |
| `session/SessionFiles.kt` | Recording and transcript files in external storage; serialised saving. |
| `session/TranslationService.kt` | Microphone foreground service, notification with Stop, wake lock. |
| `settings/SettingsStore.kt` | DataStore: audio source/gain/recording, text size, API key, dictionary override. |
| `ui/TranslationScreen.kt` | Main screen: top bar (text size, Share, Settings), panes, control bar (level, Start/Stop, Clear, audio settings). |
| `ui/SyncedPanes.kt` | Romanian/English panes with live follow and segment-aligned scroll sync. |
| `ui/AudioSettingsSheet.kt` | Bottom sheet for source, gain and recording. |
| `ui/SettingsScreen.kt`, `ui/SettingsViewModel.kt` | API key and dictionary editor (one entry per line ↔ `Dictionary`). |
| `ui/TranslationViewModel.kt`, `ui/Theme.kt` | Main screen state/actions; Material You theme. |
| `app/src/main/assets/dictionary.json` | Default dictionary, shared by the app and `tools/stream_test.py`. |
| `tools/stream_test.py` | Desktop check: streams an audio file to Soniox with the app's config and prints both languages. |

## GrapheneOS notes

- The per-app **Network** permission must be enabled (Settings → Apps → Ascult → Permissions). New apps may install with it denied; the app then shows `Unable to resolve host "stt-rt.soniox.com"`.
- Microphone access may also be gated by the global mic toggle.
