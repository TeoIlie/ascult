# Ascult

A personal Android app (Pixel, GrapheneOS) that streams the microphone to the [Soniox](https://soniox.com) real-time API and shows a live English translation of Romanian speech, built for following Romanian Orthodox sermons from across a church. It uses a pay-as-you-go API key instead of a subscription.

## Requirements

- Live Romanian → English translation, shown as it arrives (provisional text dimmed), with the Romanian alongside
- Works for a speaker across the room: selectable audio source, gain, level meter
- Keeps listening with the screen off, and reconnects if the network drops
- Custom vocabulary (Soniox context) tailored to Orthodox sermons, editable in the app
- API key entered in the app, never committed
- No backend, no Google Play Services; sideloaded via `adb`

## Setup

1. Android Studio (or the Android SDK) with platform 37, and a phone with USB debugging enabled.
2. Optional for debug builds: put your Soniox key in a gitignored `.env` at the repo root. Debug builds use it when no key is entered in the app.

   ```bash
   echo "SONIOX_API_KEY=<your key>" > .env
   ```

3. Build and install: `./gradlew installDebug` (or Run in Android Studio).
4. On GrapheneOS, allow the app's **Network** permission (Settings → Apps → Ascult → Permissions). Without it the app shows `Unable to resolve host`.

## Using the app

- **Start / Stop**: the first Start asks for microphone and notification permission. Listening continues with the screen off; the notification has a Stop button.
- **Audio settings** (the "Voice recognition · +0 dB" button): microphone source, gain, and saving a WAV recording of each session.
- **Settings**: Soniox API key and the dictionary (expected words, fixed translations, context). **Reset** restores the bundled default; **Share JSON** exports edits so they can be copied into `app/src/main/assets/dictionary.json`.
- **A− / A+** text size, **Share** the transcript, **Clear** to start a new one. Each Start adds a section labelled with its audio settings.

## Desktop tools

```bash
python3 -m venv tools/.venv && tools/.venv/bin/pip install websockets   # once
tools/.venv/bin/python tools/stream_test.py <audio file> --speed 0    # needs ffmpeg and .env
adb pull /sdcard/Android/data/com.teoilie.ascult/files/recordings/ recordings/
adb pull /sdcard/Android/data/com.teoilie.ascult/files/transcripts/ transcripts/
```

See [`tech.md`](tech.md) for the design and a map of the code.
