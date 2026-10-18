# Ascult: developer notes

Personal Android app: microphone → Soniox real-time API → live Romanian transcript and English translation, for Orthodox sermons heard from across a church. Single user (Pixel 10, GrapheneOS), sideloaded, no backend, no Play Services. User-facing docs are in `README.md`.

## Commands

```bash
./gradlew installDebug          # build and install on the USB-connected phone
./gradlew test                  # JVM unit tests (app/src/test)
./gradlew lintDebug             # keep at zero findings; report: app/build/reports/lint-results-debug.sarif
tools/.venv/bin/python tools/stream_test.py <audio> --speed 0   # desktop check against Soniox (needs .env, ffmpeg)
```

Launch and screenshot over adb: `adb shell am start -n com.teoilie.ascult/.MainActivity`, `adb exec-out screencap -p > shot.png`.

## Architecture

```text
Mic ──► AudioCapture (16 kHz mono PCM16) ──► TranslationSession ──► SonioxClient (WebSocket) ──► Soniox stt-rt
                                                  │  ▲                                                  │
                                                  │  └──────── tokens (Romanian + English, final + provisional)
                                                  ▼
                       SessionState (phase, level, segments) ──► ViewModels ──► Compose UI (SyncedPanes)
```

- `TranslationSession` lives for the whole process (held by `AscultApp`, reached via `context.app.session`). `TranslationService` is a microphone foreground service that only keeps the process, mic and CPU alive while the session listens, and stops itself when the session goes idle.
- `SessionState.phase`: IDLE, LISTENING, RECONNECTING (still capturing into the buffer), STOPPING (mic off, waiting for Soniox to finalise). `listening` is `phase != IDLE`. `error` is only for real failures; reconnecting is a neutral status.
- Audio goes into a 30 s `Channel` that outlives individual connections. The reconnect loop retries `IOException`s (including OkHttp's missed-pong failure; pings every 10 s) and Soniox 408/429/5xx with backoff 1, 2, 4, 8, 10 s. Other Soniox errors end the session. A dropped connection loses only its provisional text.
- Each Start appends a `Segment` labelled with source, gain and time; Clear resets. Transcripts autosave every 15 s and at session end (`SessionFiles`, serialised writer).
- Settings (audio source/gain/recording, text size, pane split, theme, API key, dictionary override) are in DataStore. API key: Settings value, else debug-only `BuildConfig.SONIOX_API_KEY` from `.env`. Data is excluded from backups (`res/xml/data_extraction_rules.xml`).

## Soniox protocol

- `wss://stt-rt.soniox.com/transcribe-websocket`. First a JSON text frame:

  ```json
  {
    "api_key": "<key>", "model": "stt-rt-v5",
    "audio_format": "pcm_s16le", "sample_rate": 16000, "num_channels": 1,
    "language_hints": ["ro"],
    "context": { "general": [], "text": "", "terms": [], "translation_terms": [] },
    "translation": { "type": "one_way", "target_language": "en" }
  }
  ```

- Then binary PCM frames (100 ms). An empty text frame ends the stream; Soniox then finalises and sends `"finished": true`.
- Responses: `tokens[]` with `text`, `is_final`, `translation_status` (`"translation"` = English; `"original"`/`"none"` = Romanian). Final tokens are appended once; non-final tokens replace the previous provisional tail. Errors arrive as `error_code` / `error_message`.
- Keepalive is only needed when no audio flows for 40 s; the app always streams while listening.
- `context` limit is ~10,000 characters (8,000 tokens); exceeding it fails the request. `DictionaryTest` enforces this for the bundled dictionary. Soniox advises ≤10 `general` pairs.
- Model names change between versions; check the Soniox docs when updating.

## Code map

Kotlin sources: `app/src/main/java/com/teoilie/ascult/`.

| Path | Responsibility |
| --- | --- |
| `AscultApp.kt` | Application; holds `SettingsStore` and `TranslationSession` (`context.app.…`). |
| `MainActivity.kt` | Single activity; `Screen` enum (main → settings → dictionary) with Back, theme and system-bar style. |
| `audio/AudioCapture.kt` | `AudioRecord` → flow of 100 ms PCM16 frames; level (dBFS) and gain maths. |
| `audio/WavWriter.kt` | WAV writer; `recordTo` tees frames into a recording. |
| `soniox/SonioxClient.kt` | WebSocket protocol: config, audio frames, token parsing, errors, pings. |
| `soniox/Transcript.kt` | `Transcript`, `Segment`, pure update helpers (`withTokens`, `withoutProvisional`), plain-text export. |
| `soniox/Dictionary.kt` | Soniox `context` model; loads `assets/dictionary.json`. |
| `session/TranslationSession.kt` | Lifecycle: start checks, capture → buffer → (re)connect loop, autosave, stop, clear. |
| `session/SessionFiles.kt` | Recording/transcript files in external storage; serialised saving. |
| `session/TranslationService.kt` | Microphone foreground service, Stop notification, wake lock (4 h cap). |
| `settings/SettingsStore.kt` | DataStore keys and flows; `MicSource`, `ThemeMode`. |
| `ui/TranslationScreen.kt` | Main screen: top bar (status line, ⋮ menu, Start/Stop), panes. |
| `ui/SyncedPanes.kt` | Text-only panes (`paneText`) with fading edges, live follow, segment-aligned scroll sync (`PaneLayout`), divider with labels, handle, haptics and hide preview (`snapSplit`). |
| `ui/LevelMeter.kt` | Mini input level meter. |
| `ui/AudioSettingsSheet.kt` | Source, gain, recording sheet. |
| `ui/SettingsScreen.kt`, `ui/DictionaryScreen.kt`, `ui/SettingsViewModel.kt` | Grouped settings (theme, API key dialog, about); dictionary editor (`DictionaryText` ↔ `Dictionary`). |
| `ui/TranslationViewModel.kt`, `ui/Theme.kt` | Main screen state/actions; Material 3 Expressive theme with wallpaper colours and Google Sans Flex. |
| `app/src/main/assets/dictionary.json` | Default dictionary, shared with `tools/stream_test.py`. |
| `tools/stream_test.py` | Desktop Soniox check; local outputs go in gitignored `tools/data/`. |

## Conventions and gotchas

- **Keep `SonioxClient.config()` and `build_config()` in `tools/stream_test.py` in sync** (model, format, hints, translation); each has a comment pointing at the other.
- **Look**: Material 3 Expressive (`MaterialExpressiveTheme`, `SegmentedListItem`, connected `ToggleButton`s). Icons are Material Symbols Rounded vector drawables in `res/drawable/ic_*.xml`; don't add `material-icons-extended` (minify is off). `res/font/google_sans_flex.ttf` is Google Sans Flex (OFL, licence in `assets/licenses/`) cut to Latin + Romanian with only the weight axis (300–700); re-cut it with fonttools `varLib.instancer` + `pyftsubset` if glyphs are missing.
- **Microphone permission**: lint's `MissingPermission` needs the explicit check in `TranslationSession.start()`; `listen()` is `@RequiresPermission`. Keep both.
- **Foreground service**: it must be started while the app is visible (Android 14+), which is why `TranslationViewModel.start()` starts the session first and the service second.
- **Dictionary edits**: the in-app override in DataStore hides changes to the bundled default; after changing `dictionary.json`, tap Settings → Dictionary → ⋮ → Reset to bundled default on the phone.
- **Versions** live in `gradle/libs.versions.toml`; the Gradle wrapper pins a SHA-256 (`./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sha>`).
- **Unit tests** cover pure logic only (`org.json` comes from a test dependency, since Android's is a stub on the JVM). Unit tests run with `app/` as the working directory.
- **Testing on the phone**: the user tests audio by hand (speaking, or playing `~/Downloads/20261011_*.m4a` from a laptop). Don't play audio from the desktop speakers. adb screenshots and logcat are fine, but avoid driving the UI while the user may be using the phone.
- **GrapheneOS**: per-app Network permission (DNS failure if denied); global mic toggle.
- Commits: one per logical change, imperative subject, body explaining why.
