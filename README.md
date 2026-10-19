# Ascult

A personal Android app (Pixel, GrapheneOS) for following Romanian Orthodox sermons from across a church. It streams the microphone to the [Soniox](https://soniox.com) real-time API and shows the Romanian transcript and a live English translation side by side. It uses a pay-as-you-go API key (roughly $0.10 an hour) instead of Soniox's ~$14/month app subscription. Optionally, Claude writes the English instead: a sentence at a time, with the whole sermon as context (roughly $0.30 more per sermon, with an Anthropic API key).

## Features

- Live Romanian → English: final text in full colour, still-changing text dimmed, Romanian and English panes scrolled in sync and resizable.
- Two translators: Soniox (instant, a few words at a time) or Claude (whole sentences in formal liturgical English, a second or two after each full stop).
- Tuned for a distant speaker: microphone source picker, software gain, live level meter, optional WAV recording to compare settings.
- Material 3 Expressive look in your wallpaper's colours, light or dark (or follow the phone), in Google Sans Flex.
- Keeps listening with the screen off (foreground service with a Stop notification), and keeps the screen awake while the app is open.
- Survives network drops: audio is buffered for 30 s and sent once the connection is back; stalled connections are detected within ~20 s.
- Orthodox church vocabulary and context sent to Soniox (and, in part, to Claude), editable in the app.
- Transcripts can be shared and are saved automatically.
- No backend and no Google Play Services; installed with `adb`.

## Setup

1. Android Studio (or the Android SDK) with platform 37, and a phone on Android 14+ with USB debugging enabled.
2. Optional for debug builds: put your API keys in a gitignored `.env` at the repo root. Debug builds use them when no key is entered in the app; release builds never contain them. The Anthropic key is only needed for Claude translation.

   ```bash
   echo "SONIOX_API_KEY=<your key>" > .env
   echo "ANTHROPIC_API_KEY=<your key>" >> .env
   ```

3. Build and install: `./gradlew installDebug` (or Run in Android Studio).
4. On GrapheneOS, allow the app's **Network** permission (Settings → Apps → Ascult → Permissions). New apps may install with it denied, and the app then shows `Unable to resolve host "stt-rt.soniox.com"` (or `api.anthropic.com`). Microphone access can also be blocked by the global microphone toggle.

## Using the app

- **Start / Stop** (the button at the bottom right; a microphone when idle, a square while listening): the first Start asks for microphone and notification permission. Listening continues with the screen off; the notification has a Stop button. After Stop, the app waits briefly for Soniox to finish the last words.
- **Sections**: each Start adds a new paragraph, so text is kept across Stop/Start. The panes show text only; shared and saved transcripts label each section with its microphone source, gain and start time. **Clear** (in ⋮, while stopped) starts a new transcript.
- **Status** (bottom left): a small level meter, what is happening (Ready, Listening, Recording, Reconnecting, Finishing, or an error in red) and the audio settings in use, or a translation notice ("Claude: retrying", "Claude translation failed: …").
- **Audio settings** (tap the status, or ⋮ → Audio settings):
  - **Microphone source**: Voice recognition (default), Unprocessed, Mic or Camcorder. Each applies different phone processing; which works best for a distant speaker has to be found by ear and by transcript quality. Applies from the next Start.
  - **Gain**: 0 to +24 dB, applied immediately. It raises quiet input but does not reduce background noise.
  - **Translation**: **Soniox** (default) or **Claude**. Applies from the next Start.
    - Soniox translates as it hears, a few words at a time, so the English can read choppily.
    - Claude translates each finished Romanian sentence as part of one conversation, so it knows the whole sermon so far. Very short sentences ("De aceea.") wait to go with the next one. Between sentences, an optional dimmed two-line caption under the English (**Live caption**, on by default) shows Soniox's live English, so you can follow a long sentence before Claude's version arrives. After Stop, the last sentences are translated before the session ends. If Claude fails for good (for example an invalid key), the English pane falls back to Soniox's English and listening continues. Needs an Anthropic API key.
  - **Save recording**: saves exactly what is sent to Soniox as a WAV named `<time>_<SOURCE>_+<gain>dB.wav`.
- **Settings** (⋮ → Settings):
  - **Theme**: System, Light or Dark (Dark is handy in church even when the phone is light).
  - **Vocabulary and context** (under Sermon): its own screen with expected words, fixed translations (`țarina = field`), the setting (`domain: Orthodox sermon`) and background text, one entry per line. Each field says whether Soniox, Claude or both use it. **Share** exports your edits as JSON so they can be copied into `app/src/main/assets/dictionary.json`; **⋮ → Reset to bundled default** restores the default.
  - **API keys**: **Soniox API key** and **Anthropic API key** (for Claude translation). Tap to enter; stored on the phone only (excluded from backups). The Anthropic key needs API credit from the Claude Console; a Claude Pro subscription does not include API use.
- **Text size** (the TT icon) opens a − / + stepper; **⋮ → Share transcript** sends the transcript as plain text.
- **Divider**: the line between the panes carries their names ("Română ↑", "English ↓") and a handle. Drag it to give one language more room; the handle lights up and the phone ticks when you touch it. Dragged nearly to the top or bottom, that pane dims with "Release to hide…" and a firmer tick; letting go hides it. Tap the hidden pane's name (or double-tap the divider) to bring it back. The position is remembered.
- **Scrolling**: both panes follow new text, which fades out at a pane's edge where there is more to scroll. Scroll either pane to review, and the other stays aligned; **Jump to live** (or scrolling to the end) resumes following.
- **Reconnecting**: "Reconnecting… audio is kept" in the status line means the app is still recording and will catch up once the network is back. Red text is a real error (for example an invalid API key).

Transcripts (saved every 15 s while listening and at Stop) and recordings are on the phone in `/sdcard/Android/data/com.teoilie.ascult/files/`. Sections translated by Claude keep Soniox's English too, as `EN (Soniox):`.

## Vocabulary and context (the dictionary)

Soniox accepts a `context` with each session, and it made a clear difference in testing (farming metaphors in the sample sermon went from "toll" and "the slave's body" to "field" and "pickaxe"). The default in `app/src/main/assets/dictionary.json` covers:

- **Setting and background**: an Orthodox priest preaching in a reverberant church, with Scripture quotations and teachings of the Church Fathers.
- **Expected words**: church spellings (`Iisus Hristos`), services, sacraments, church objects, clergy titles, feasts, saints and parables.
- **Fixed translations**: standard English Orthodox terms (Theotokos, Great Lent, the Holy Mysteries, His Grace, Holy Pascha).

Claude translation gets the setting and fixed translations too, after its instructions in `app/src/main/assets/claude_translation_prompt.txt`.

Soniox rejects a context over about 10,000 characters; the default uses about 6,800, leaving room for words added in person.

## Desktop tools

`tools/stream_test.py` streams an audio file to Soniox with the same settings and dictionary as the app and prints both languages. It is handy for checking dictionary changes against a recording.

```bash
python3 -m venv tools/.venv && tools/.venv/bin/pip install websockets      # once; also needs ffmpeg
tools/.venv/bin/python tools/stream_test.py <audio file> --speed 0       # --no-context to compare without the dictionary
```

Local test data goes in the gitignored `tools/data/`:

```bash
adb pull /sdcard/Android/data/com.teoilie.ascult/files/recordings/ tools/data/recordings/
adb pull /sdcard/Android/data/com.teoilie.ascult/files/transcripts/ tools/data/transcripts/
tools/.venv/bin/python tools/stream_test.py tools/data/recordings/<file>.wav --speed 0 > tools/data/runs/<name>.txt
```

## Sharing the app

It is built for one phone. Making it public would need short-lived Soniox keys handed out by a small server (instead of each user's own key), release signing, and a configurable language pair.

Developer notes (architecture, code map, conventions) are in [`CLAUDE.md`](CLAUDE.md).
