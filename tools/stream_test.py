#!/usr/bin/env python3
"""Stream an audio file to the Soniox real-time API, as the app will, and print
the Romanian transcript and English translation.

Audio is decoded with ffmpeg to 16 kHz mono pcm_s16le (the format the app sends)
and paced at real time unless --speed is given.

Usage:
    tools/.venv/bin/python tools/stream_test.py AUDIO [--no-context] [--speed N] [--dump FILE]
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
from pathlib import Path

import websockets

URL = "wss://stt-rt.soniox.com/transcribe-websocket"
MODEL = "stt-rt-v5"
SAMPLE_RATE = 16000
CHUNK_MS = 100
CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS // 1000
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DICTIONARY = ROOT / "app/src/main/assets/dictionary.json"

DIM = "\033[2m"
RESET = "\033[0m"


def load_api_key() -> str | None:
    """SONIOX_API_KEY from the environment, else from a gitignored .env in the repo root."""
    if key := os.environ.get("SONIOX_API_KEY"):
        return key
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            name, _, value = line.partition("=")
            if name.strip().removeprefix("export ").strip() == "SONIOX_API_KEY":
                return value.strip().strip("'\"")
    return None


def decode_pcm(path: str) -> bytes:
    return subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "s16le", "-"],
        check=True,
        capture_output=True,
    ).stdout


def build_config(api_key: str, context: dict | None) -> dict:
    config = {
        "api_key": api_key,
        "model": MODEL,
        "audio_format": "pcm_s16le",
        "sample_rate": SAMPLE_RATE,
        "num_channels": 1,
        "language_hints": ["ro"],
        "translation": {"type": "one_way", "target_language": "en"},
    }
    if context:
        config["context"] = context
    return config


async def send_audio(ws, pcm: bytes, speed: float) -> None:
    delay = CHUNK_MS / 1000 / speed if speed > 0 else 0
    for i in range(0, len(pcm), CHUNK_BYTES):
        await ws.send(pcm[i : i + CHUNK_BYTES])
        await asyncio.sleep(delay)
    await ws.send("")  # empty text frame ends the stream


def render(final: dict, provisional: dict) -> None:
    if not sys.stdout.isatty():
        return  # piped output: only the final transcript is printed, at the end
    sys.stdout.write("\033[H\033[2J")
    for label, key in (("RO", "original"), ("EN", "translation")):
        sys.stdout.write(f"── {label} ──\n{final[key]}{DIM}{provisional[key]}{RESET}\n\n")
    sys.stdout.flush()


async def run(args) -> None:
    api_key = load_api_key()
    if not api_key:
        sys.exit("SONIOX_API_KEY is not set (export it or add it to .env in the repo root)")

    context = None if args.no_context else json.loads(Path(args.dictionary).read_text())
    pcm = decode_pcm(args.audio)
    final = {"original": "", "translation": ""}
    dump = open(args.dump, "w") if args.dump else None

    # No client pings: when audio is sent faster than real time, pongs queue behind it and time out.
    async with websockets.connect(URL, ping_interval=None) as ws:
        await ws.send(json.dumps(build_config(api_key, context)))
        sender = asyncio.create_task(send_audio(ws, pcm, args.speed))

        async for message in ws:
            response = json.loads(message)
            if dump:
                dump.write(message + "\n")
                dump.flush()
            if response.get("error_code"):
                sys.exit(f"Soniox error {response['error_code']}: {response.get('error_message')}")

            provisional = {"original": "", "translation": ""}
            for token in response.get("tokens", []):
                # "none" means the token was not part of a translation pair; treat it as original text.
                key = "translation" if token.get("translation_status") == "translation" else "original"
                if token["is_final"]:
                    final[key] += token["text"]
                else:
                    provisional[key] += token["text"]
            render(final, provisional)

            if response.get("finished"):
                break

        await sender

    if not sys.stdout.isatty():
        print(f"── RO ──\n{final['original']}\n\n── EN ──\n{final['translation']}")
    if dump:
        dump.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("audio")
    parser.add_argument("--dictionary", default=DEFAULT_DICTIONARY, help="context JSON (default: app asset)")
    parser.add_argument("--no-context", action="store_true", help="send no dictionary, for comparison")
    parser.add_argument("--speed", type=float, default=1.0, help="playback speed multiplier; 0 = as fast as possible")
    parser.add_argument("--dump", help="write raw JSON responses to this file")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
