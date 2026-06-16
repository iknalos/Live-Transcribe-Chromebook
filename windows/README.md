# local-transcribe

Fully **offline** speech-to-text. After a one-time model download, **nothing
leaves your machine** — no cloud APIs, no telemetry. Two ways to use it:

| | What | Best for |
|---|---|---|
| **Live app** | `live_transcribe.py` — window: Start/Stop, live preview, speaker tags | Real-time transcription of a **conversation** |
| **CLI** | `transcribe.py` — transcribe a file | Transcribing **existing audio/video files** |

Engine: [`faster-whisper`](https://github.com/SYSTRAN/faster-whisper); the live
app adds [`RealtimeSTT`](https://github.com/KoljaB/RealtimeSTT) + Silero VAD for
continuous capture. CPU by default.

---

## Live conversation transcription (the window)

**Open it:** double-click **`Live Transcribe.bat`** or the **Live Transcribe**
desktop shortcut.

Click **Start listening** and talk. Words appear **live** as you speak (a fast
preview model), and the accurate, timestamped line lands when the speaker pauses.
A **status dot** shows what it's doing: gray *Idle* → green **Listening** → red
**Hearing you** → orange *Transcribing*. **Stop** ends; **Save** writes a `.txt`.

- **Speaker tags:** press **0–4** (or click the buttons) — 0 = Me/interviewer,
  1–4 = each person. Tap the number as you hand the mic over; lines are
  color-coded per speaker.
- **Final model picker:** `small.en` (default, fast), `base.en`/`tiny.en`
  (faster), `distil-large-v3` (most accurate, may lag on CPU). The live preview
  always uses `tiny.en`.
- **In-person chats:** your laptop mic hears everyone — works out of the box.
- **Online calls:** the mic won't cleanly capture the *other* person (their voice
  comes from your speakers) — that needs system-audio capture; ask and I'll add it.
- **Troubleshooting:** window does nothing → run **`Live Transcribe (console).bat`**
  to see errors. Mic seems dead → run **`Mic Check.bat`** to test your input level.

## Transcribe a file (CLI)

```powershell
conda run -n voicetx --no-capture-output python C:\Users\test\whisper-transcribe\transcribe.py recording.mp3
conda run -n voicetx --no-capture-output python C:\Users\test\whisper-transcribe\transcribe.py recording.mp3 > transcript.txt
```

`--task transcribe` (default) keeps the language; `--model large-v3` for max
accuracy; `--offline` to forbid any network. Transcript → stdout, status → stderr.

---

## Setup (already done by the installer)

```powershell
conda create -n voicetx python=3.11 -y
conda run -n voicetx pip install faster-whisper sounddevice RealtimeSTT silero-vad
```

Models are cached under `%USERPROFILE%\.cache\huggingface\` after first use.
English-focused (`.en` / `distil` models are English-only); for other languages
use `--model large-v3-turbo --language auto` with the CLI.
