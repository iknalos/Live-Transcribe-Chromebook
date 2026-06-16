#!/usr/bin/env python3
"""
local-transcribe -- fully offline English speech-to-text.

Engine : faster-whisper (CTranslate2). No torch, no cloud. Runs on CPU.
Privacy: after the one-time model download, NOTHING leaves your machine.
         Pass --offline to hard-fail if the model isn't already cached.

Transcript text goes to STDOUT (one line per segment); all status/progress
goes to STDERR -- so `python transcribe.py clip.mp3 > out.txt` gives a clean
transcript file.

Examples
--------
  # Transcribe an audio/video file:
  python transcribe.py recording.mp3

  # Transcribe live from your microphone (press Enter to stop):
  python transcribe.py --mic

  # Lighter/faster model for snappier real-time dictation:
  python transcribe.py --mic --model small.en

  # Highest accuracy (slower on CPU):
  python transcribe.py recording.mp3 --model large-v3

  # Save a clean transcript:
  python transcribe.py recording.mp3 > transcript.txt
"""
from __future__ import annotations

import argparse
import sys
import time


def log(*args) -> None:
    """Status output -> stderr, so stdout stays a clean transcript."""
    print(*args, file=sys.stderr, flush=True)


def load_model(model_size: str, compute: str, offline: bool):
    from faster_whisper import WhisperModel

    return WhisperModel(
        model_size,
        device="cpu",
        compute_type=compute,
        local_files_only=offline,
    )


def record_until_enter(samplerate: int = 16000):
    """Record mono float32 audio from the default mic until Enter is pressed."""
    import threading  # noqa: F401  (kept for clarity; callback is on PortAudio thread)

    import numpy as np
    import sounddevice as sd

    frames: list = []

    def callback(indata, _frames, _time, status):
        if status:
            log(status)
        frames.append(indata.copy())

    log("Recording... press Enter to stop.")
    with sd.InputStream(samplerate=samplerate, channels=1, dtype="float32",
                        callback=callback):
        input()  # blocks until the user hits Enter
    if not frames:
        return np.zeros(0, dtype="float32")
    return np.concatenate(frames, axis=0).flatten()


def main() -> int:
    p = argparse.ArgumentParser(
        description="Offline English speech-to-text (faster-whisper, CPU)."
    )
    p.add_argument("audio", nargs="?",
                   help="Path to an audio/video file. Omit when using --mic.")
    p.add_argument("--mic", action="store_true",
                   help="Record from the microphone instead of reading a file.")
    p.add_argument("--model", default="distil-large-v3",
                   help="distil-large-v3 (default, English, fast+accurate) | "
                        "small.en | base.en (lighter) | large-v3 (max accuracy, "
                        "slower) | large-v3-turbo (multilingual).")
    p.add_argument("--language", default="en",
                   help="Source language code. Default: en. Use 'auto' to detect "
                        "(only works with a multilingual model).")
    p.add_argument("--task", choices=["transcribe", "translate"], default="transcribe",
                   help="transcribe (default) keeps the spoken language; translate "
                        "outputs English from a multilingual model.")
    p.add_argument("--compute", default="int8",
                   help="CTranslate2 compute type: int8 (fastest on CPU) | "
                        "int8_float32 | float32. Default: int8.")
    p.add_argument("--offline", action="store_true",
                   help="Refuse all network access; the model must already be cached.")
    args = p.parse_args()

    if not args.mic and not args.audio:
        p.error("give an audio file path, or use --mic")

    language = None if args.language.lower() in ("auto", "none", "") else args.language

    log(f"Loading '{args.model}' (cpu/{args.compute})... "
        f"first run downloads it once, then it's cached.")
    t0 = time.time()
    model = load_model(args.model, args.compute, args.offline)
    log(f"Model ready in {time.time() - t0:.1f}s.")

    source = record_until_enter() if args.mic else args.audio
    if args.mic and getattr(source, "size", 0) == 0:
        log("No audio captured.")
        return 1

    t0 = time.time()
    segments, info = model.transcribe(
        source,
        task=args.task,
        language=language,
        beam_size=5,
        vad_filter=True,  # skip silence so CPU isn't wasted on dead air
    )
    log(f"Detected language: {info.language} (p={info.language_probability:.2f}) "
        f"| task: {args.task}")

    n = 0
    for seg in segments:
        print(seg.text.strip())  # transcript -> stdout
        n += 1
    log(f"Done: {n} segment(s) in {time.time() - t0:.1f}s.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
