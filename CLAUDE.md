# Ascult: developer notes

Personal Android app: microphone → Soniox real-time API → live Romanian transcript and English translation (by Soniox, or optionally by Claude from finished sentences), for Orthodox sermons heard from across a church. Single user (Pixel 10, GrapheneOS), sideloaded, no backend, no Play Services. User-facing docs are in `README.md`.

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
                                                  ▼            Claude mode: finished Romanian sentences
                       SessionState (phase, level, segments) ◄──► ClaudeTranslator ──► ClaudeClient (HTTP) ──► Claude Messages API
                                                  │
                                                  ▼
                                    ViewModels ──► Compose UI (SyncedPanes)
```

- `TranslationSession` lives for the whole process (held by `AscultApp`, reached via `context.app.session`). `TranslationService` is a microphone foreground service that only keeps the process, mic and CPU alive while the session listens, and stops itself when the session goes idle.
- `SessionState.phase`: IDLE, LISTENING, RECONNECTING (still capturing into the buffer), STOPPING (mic off, waiting for Soniox to finalise). `listening` is `phase != IDLE`. `error` is only for real failures; reconnecting is a neutral status.
- Audio goes into a 30 s `Channel` that outlives individual connections. The reconnect loop retries `IOException`s (including OkHttp's missed-pong failure; pings every 10 s) and Soniox 408/429/5xx with backoff 1, 2, 4, 8, 10 s. Other Soniox errors end the session. A dropped connection loses only its provisional text.
- History: each Start→Stop run is one `HistoryEntry` (`HistoryStore`, held by `AscultApp`): a folder `history/<yyyyMMdd-HHmmss>/` with `entry.json` and `audio.wav` → `audio.m4a`. The session shows one `Segment` at a time; its draft (`saved = false`) autosaves every 15 s and at session end (serialised writer). Stop sets `SessionState.pending` (if there is Romanian), which shows `SaveSermonSheet` (not dismissable; Start refuses while pending). `save(title)` applies when the run has finished; `delete()` cancels it and moves the folder to `history/.trash` until the Undo snackbar goes (`forgetDeleted` → `HistoryStore.forget(ids)`, each Undo forgetting only its own entries; the trash is also emptied at launch). `start()` and `undoDelete()` first join a cancelled run so its cleanup can't touch the next one. Runs without Romanian are discarded silently; drafts found at launch are marked saved (or dropped if empty). Saved WAVs are encoded to AAC (`encodeM4a`, one at a time, beside the writer; leftovers retried at launch). `SessionEvent` (Saved/Deleted) drives the main screen's snackbar.
- Claude mode (`AudioSettings.translator`, fixed per segment at Start): Soniox still runs with translation on (its English feeds the live caption and is the fallback). A translation job in `listen()` sends the current segment's final Romanian to Claude via `nextChunk` (sentence ends; fragments under 4 words wait; run-ons over 40 words are cut), one request at a time, and appends the English to `Segment.claude`. Retryable failures back off like reconnects and show `SessionState.notice`; other failures mark the segment's Claude text failed (pane falls back to Soniox's English) without stopping. On Stop, the rest is flushed after Soniox finishes (stop timeout grows by 10 s).
- Settings (audio source/gain/recording/translator/live caption, text size, pane split, theme, API keys, dictionary override) are in DataStore. API keys: Settings value, else debug-only `BuildConfig.SONIOX_API_KEY` / `ANTHROPIC_API_KEY` from `.env`. Data is excluded from backups (`res/xml/data_extraction_rules.xml`).

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

## Claude translation

- `POST https://api.anthropic.com/v1/messages` with `x-api-key` and `anthropic-version: 2023-06-01`; model `CLAUDE_MODEL` (`claude-haiku-4-5`), `max_tokens` 2048, no thinking.
- One conversation per run (each Start begins a new one): `system` is `assets/claude_translation_prompt.txt` plus the dictionary's `general` and `translation_terms` (`systemPrompt`); each turn is user = Romanian chunk in `<romanian>` tags (unwrapped, Haiku answered "Is it working?" instead of translating it), assistant = English. Only successful turns are kept, so a failed chunk is resent merged with what followed and roles keep alternating.
- Top-level `"cache_control": {"type": "ephemeral"}` caches the growing conversation (5-min TTL, refreshed by each request). Haiku 4.5 only caches prompts of 4,096+ tokens, so the first minutes of a sermon are uncached. Each request logs its usage to logcat (tag `Ascult`): check that `cache read` grows.
- Errors: `{"error": {"type", "message"}}`; 408/429/5xx (incl. 529 overloaded) and `IOException`s are retried.

## Code map

Kotlin sources: `app/src/main/java/com/teoilie/ascult/`.

| Path | Responsibility |
| --- | --- |
| `AscultApp.kt` | Application; holds `SettingsStore`, `HistoryStore` and `TranslationSession` (`context.app.…`); `LOG_TAG`. |
| `MainActivity.kt` | Single activity; `Screen` enum with parents (main → settings → dictionary, main → history → entry) for Back, theme and system-bar style. |
| `audio/AudioCapture.kt` | `AudioRecord` → flow of 100 ms PCM16 frames; level (dBFS) and gain maths. |
| `audio/WavWriter.kt` | WAV writer; `recordTo` tees frames into a recording. |
| `audio/AudioEncoder.kt` | `encodeM4a`: WAV → AAC-LC .m4a with `MediaCodec` + `MediaMuxer`. |
| `soniox/SonioxClient.kt` | WebSocket protocol: config, audio frames, token parsing, errors, pings. |
| `soniox/Transcript.kt` | `Transcript`, `Segment`, pure update helpers (`withTokens`, `withoutProvisional`), plain-text export. |
| `soniox/Dictionary.kt` | Soniox `context` model; loads `assets/dictionary.json`. |
| `translation/SentenceChunker.kt` | `nextChunk`: which finished Romanian to send to Claude next. |
| `translation/ClaudeClient.kt` | Messages API over OkHttp: request/response JSON, `ClaudeException`. |
| `translation/ClaudeTranslator.kt` | The sermon conversation; `systemPrompt` from instructions + dictionary. |
| `session/TranslationSession.kt` | Lifecycle: start checks, capture → buffer → (re)connect loop, Claude translation job, autosave, stop, save/delete/undo; `SessionEvent`. |
| `history/HistoryEntry.kt` | One saved run: JSON, `defaultTitle`, `details`/`summary` labels. |
| `history/HistoryStore.kt` | Entry folders under a root (external files `history/`): create, serialised writes, trash/restore/forget, load at launch, compression. JVM-tested with a temp folder. |
| `session/TranslationService.kt` | Microphone foreground service, Stop notification, wake lock (4 h cap). |
| `settings/SettingsStore.kt` | DataStore keys and flows; `MicSource`, `Translator`, `ThemeMode`. |
| `ui/TranslationScreen.kt` | Main screen: panes, docked bottom bar (status, ⋮ menu, Start/Stop); `statusText`. |
| `ui/SyncedPanes.kt` | Text-only panes (`paneText`, `PaneSide`) with fading edges, live follow, segment-aligned scroll sync (`PaneLayout`), haptics and hide preview; Claude mode's `liveCaption`. |
| `ui/PaneDivider.kt` | Divider between the panes: labels, handle, drag/double-tap/accessibility, `snapSplit`. |
| `ui/LevelMeter.kt` | Mini input level meter. |
| `ui/AudioSettingsSheet.kt` | Source, gain, translation, recording sheet. |
| `ui/SaveSermonSheet.kt` | Save/Delete sheet after Stop. |
| `ui/HistoryScreen.kt`, `ui/EntryScreen.kt`, `ui/HistoryViewModel.kt` | History list (`historyGroups`, selection, Undo) and a saved sermon (read-only `SyncedPanes`, ⋮ share/rename/delete); the view model is activity-scoped so both share the Undo. |
| `ui/PlayerBar.kt` | `MediaPlayer` bar: seek, ±10 s, play/pause, speed. |
| `ui/Components.kt` | Shared bits: `share()`, `shareFile()` (FileProvider, `res/xml/file_paths.xml`), `TonalIcon`, `BackButton`, `IconMenu`, `SettingsColumn`/`SettingsGroup`, `ConnectedToggleGroup`. |
| `ui/SettingsScreen.kt`, `ui/DictionaryScreen.kt`, `ui/SettingsViewModel.kt` | Grouped settings (theme, vocabulary and context, API key dialogs, about); dictionary editor ("Vocabulary and context") (`DictionaryText` ↔ `Dictionary`). |
| `ui/TranslationViewModel.kt`, `ui/Theme.kt` | Main screen state/actions; Material 3 Expressive theme with wallpaper colours and Google Sans Flex. |
| `app/src/main/assets/dictionary.json` | Default dictionary, shared with `tools/stream_test.py`. |
| `app/src/main/assets/claude_translation_prompt.txt` | Claude's translation instructions (the dictionary is appended). |
| `tools/stream_test.py` | Desktop Soniox check; local outputs go in gitignored `tools/data/`. |

## Conventions and gotchas

- **Keep `SonioxClient.config()` and `build_config()` in `tools/stream_test.py` in sync** (model, format, hints, translation); each has a comment pointing at the other.
- **Look**: Material 3 Expressive (`MaterialExpressiveTheme`, `SegmentedListItem`, connected `ToggleButton`s). Icons are Material Symbols Rounded vector drawables in `res/drawable/ic_*.xml`; don't add `material-icons-extended` (minify is off). `res/font/google_sans_flex.ttf` is Google Sans Flex (OFL, licence in `assets/licenses/`) cut to Latin + Romanian with only the weight axis (300–700); re-cut it with fonttools `varLib.instancer` + `pyftsubset` if glyphs are missing.
- **Claude over plain HTTP**: `ClaudeClient` uses OkHttp + `org.json` on purpose. The official `anthropic-java` SDK added about 44 MB to the APK and expects OkHttp 4; don't reintroduce it.
- **Microphone permission**: lint's `MissingPermission` needs the explicit check in `TranslationSession.start()`; `listen()` is `@RequiresPermission`. Keep both.
- **Foreground service**: it must be started while the app is visible (Android 14+), which is why `TranslationViewModel.start()` starts the session first and the service second.
- **Dictionary edits**: the in-app override in DataStore hides changes to the bundled default; after changing `dictionary.json`, tap Settings → Vocabulary and context → ⋮ → Reset to bundled default on the phone. In code and JSON it is still the "dictionary".
- **Versions** live in `gradle/libs.versions.toml`; the Gradle wrapper pins a SHA-256 (`./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sha>`).
- **Unit tests** cover pure logic only (`org.json` comes from a test dependency, since Android's is a stub on the JVM). Unit tests run with `app/` as the working directory.
- **Testing on the phone**: the user tests audio by hand (speaking, or playing `~/Downloads/20261011_*.m4a` from a laptop). Don't play audio from the desktop speakers. adb screenshots and logcat are fine, but avoid driving the UI while the user may be using the phone.
- **GrapheneOS**: per-app Network permission (DNS failure if denied); global mic toggle.
- Commits: one per logical change, imperative subject, body explaining why.
