# Soniox Live Translate

A personal Android app (Pixel, GrapheneOS) that streams the microphone to the [Soniox](https://soniox.com) real-time API and shows a live English translation of Romanian speech. It uses a pay-as-you-go API key instead of a subscription.

## Requirements

- Live Romanian → English translation, shown as it arrives (provisional text dimmed)
- Works for a speaker across the room: selectable audio source, gain, level meter
- Keeps listening with the screen off
- API key entered in the app, never committed
- No backend, no Google Play Services; sideloaded via `adb`

See [`tech.md`](tech.md) for the design.
