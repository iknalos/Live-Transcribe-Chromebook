#!/usr/bin/env python3
"""
Live Transcribe -- fully offline, real-time English conversation transcription
with manual speaker tagging, a big status banner, and Pause/Resume.

Designed for one mic passed around a group: tap who's about to speak (buttons or
number keys 0-4) as you hand the mic over. Consecutive speech from the same
speaker is merged into one paragraph; switching speakers starts a new paragraph.
Pause mutes the mic without unloading the model (for breaks); Resume continues.
After the one-time model download, NOTHING goes online.

Tuned for low latency on CPU: a small English model + greedy decoding. Pick a
bigger model in the dropdown for more accuracy.

Engine : RealtimeSTT (faster-whisper + Silero VAD). Runtime: CPU.
"""
from __future__ import annotations

import datetime as dt
import multiprocessing
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

# the dropdown is the speed<->accuracy dial; base.en benchmarked fastest+accurate
MODELS = ["base.en", "tiny.en", "small.en", "distil-large-v3"]
DEFAULT_MODEL = "base.en"
BEAM_SIZE = 1            # 1 = greedy = fastest on CPU (vs default 5)
SILENCE_SECS = 0.5      # end-of-speech pause before a chunk is finalized

SPEAKERS = [
    (0, "Me", "#444444"),
    (1, "Speaker 1", "#1565C0"),
    (2, "Speaker 2", "#2E7D32"),
    (3, "Speaker 3", "#C62828"),
    (4, "Speaker 4", "#6A1B9A"),
]
NAME = {v: n for v, n, _ in SPEAKERS}

# big status banner: name -> (background, foreground, caption)
STATES = {
    "idle":         ("#E0E0E0", "#444444", "Idle — press Start to begin"),
    "loading":      ("#FFE0B2", "#7A4F01", "Loading model…  (first time only)"),
    "listening":    ("#C8E6C9", "#1B5E20", "●  LISTENING — speak anytime"),
    "hearing":      ("#FFCDD2", "#B71C1C", "●  HEARING YOU…"),
    "transcribing": ("#FFE0B2", "#7A4F01", "Transcribing…"),
    "paused":       ("#FFF9C4", "#827717", "❚❚  PAUSED — press Resume to continue"),
    "stopped":      ("#E0E0E0", "#444444", "Stopped — press Start to resume"),
}


class LiveTranscribeApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("Live Transcribe — offline, speaker-tagged")
        root.geometry("780x600")
        root.minsize(580, 420)

        self.recorder = None
        self.worker: threading.Thread | None = None
        self.running = False
        self.paused = False
        self.current_speaker = 0
        self.last_block_speaker = None   # speaker of the paragraph currently being written
        self.ui_q: "queue.Queue[tuple[str, object]]" = queue.Queue()

        # --- control bar ---
        bar = ttk.Frame(root, padding=8)
        bar.pack(fill="x")
        self.btn = ttk.Button(bar, text="●  Start", command=self.toggle, width=10)
        self.btn.pack(side="left")
        self.pause_btn = ttk.Button(bar, text="❚❚  Pause", command=self.toggle_pause,
                                    width=11, state="disabled")
        self.pause_btn.pack(side="left", padx=(6, 0))
        ttk.Label(bar, text="   Model:").pack(side="left")
        self.model_var = tk.StringVar(value=DEFAULT_MODEL)
        self.model_box = ttk.Combobox(bar, textvariable=self.model_var, values=MODELS,
                                      width=14, state="readonly")
        self.model_box.pack(side="left")
        ttk.Button(bar, text="Save…", command=self.save).pack(side="right")
        ttk.Button(bar, text="Clear", command=self.clear).pack(side="right", padx=(0, 6))

        # --- speaker selector ---
        spk = ttk.Frame(root, padding=(8, 0, 8, 4))
        spk.pack(fill="x")
        ttk.Label(spk, text="Tag as (press 0–4): ").pack(side="left")
        self.speaker_var = tk.IntVar(value=0)
        for val, label, color in SPEAKERS:
            tk.Radiobutton(
                spk, text=f"{label} [{val}]", variable=self.speaker_var, value=val,
                indicatoron=0, width=11, padx=4, pady=3,
                command=self.on_speaker_change, fg=color, selectcolor="#e8e8e8",
            ).pack(side="left", padx=2)
        self.now_lbl = ttk.Label(spk, text="  → tagging: Me")
        self.now_lbl.pack(side="left", padx=8)

        # --- BIG status banner ---
        bg, fg, cap = STATES["idle"]
        self.banner = tk.Label(root, text=cap, font=("Segoe UI", 13, "bold"),
                               bg=bg, fg=fg, pady=10)
        self.banner.pack(fill="x", padx=8, pady=(2, 4))

        # --- transcript ---
        self.text = scrolledtext.ScrolledText(root, wrap="word",
                                              font=("Segoe UI", 12), padx=10, pady=10,
                                              spacing3=6)  # gap after wrapped paragraphs
        self.text.pack(fill="both", expand=True, padx=8, pady=(0, 2))
        self.text.tag_configure("ts", foreground="#999999")
        for val, _, color in SPEAKERS:
            self.text.tag_configure(f"spk{val}", foreground=color,
                                    font=("Segoe UI", 12, "bold"))
        self.text.configure(state="disabled")

        # --- bottom transient message ---
        status = ttk.Frame(root, padding=(8, 2, 8, 4))
        status.pack(fill="x", side="bottom")
        self.msg_lbl = tk.Label(status, text="100% offline — nothing leaves this PC",
                                anchor="w", fg="#999999", font=("Segoe UI", 9))
        self.msg_lbl.pack(side="left")

        for val, _, _ in SPEAKERS:
            root.bind_all(str(val), lambda e, v=val: self.set_speaker(v))
        root.bind_all("m", lambda e: self.set_speaker(0))
        root.bind_all("M", lambda e: self.set_speaker(0))
        root.bind_all("<space>", lambda e: self.toggle_pause())  # spacebar = pause/resume

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(60, self.drain_queue)

    # ---- speaker selection ----
    def set_speaker(self, val: int):
        self.speaker_var.set(val)
        self.on_speaker_change()

    def on_speaker_change(self):
        self.current_speaker = self.speaker_var.get()
        self.now_lbl.configure(text=f"  → tagging: {NAME[self.current_speaker]}")

    # ---- start / stop ----
    def toggle(self):
        self.stop() if self.running else self.start()

    def start(self):
        self.running = True
        self.paused = False
        self.btn.configure(text="■  Stop")
        self.pause_btn.configure(text="❚❚  Pause", state="normal")
        self.model_box.configure(state="disabled")
        self.set_state("loading")
        self.worker = threading.Thread(target=self._run, args=(self.model_var.get(),),
                                       daemon=True)
        self.worker.start()

    def stop(self):
        self.running = False
        self.paused = False
        self._interrupt()
        self.btn.configure(text="●  Start")
        self.pause_btn.configure(text="❚❚  Pause", state="disabled")
        self.model_box.configure(state="readonly")
        self.set_state("stopped")

    # ---- pause / resume ----
    def toggle_pause(self):
        if not self.running or self.recorder is None:
            return
        if not self.paused:
            self.paused = True
            try:
                self.recorder.set_microphone(False)
            except Exception:
                pass
            self.pause_btn.configure(text="▶  Resume")
            self.set_state("paused")
        else:
            self.paused = False
            try:
                self.recorder.clear_audio_queue()
                self.recorder.set_microphone(True)
            except Exception:
                pass
            self.pause_btn.configure(text="❚❚  Pause")
            self.set_state("listening")

    def _interrupt(self):
        r = self.recorder
        if r is None:
            return
        for name in ("abort", "stop"):
            fn = getattr(r, name, None)
            if callable(fn):
                try:
                    fn()
                except Exception:
                    pass

    # ---- RealtimeSTT callbacks (engine thread -> queue) ----
    def _on_speech_start(self, *_):
        self.ui_q.put(("state", "hearing"))

    def _on_speech_stop(self, *_):
        self.ui_q.put(("state", "transcribing"))

    # ---- worker thread ----
    def _run(self, model_name: str):
        try:
            from RealtimeSTT import AudioToTextRecorder
        except Exception as exc:  # pragma: no cover
            self.ui_q.put(("error", f"RealtimeSTT isn't installed:\n{exc}"))
            return

        base = dict(
            model=model_name, language="en", compute_type="int8", device="cpu",
            use_microphone=True, spinner=False,
            beam_size=BEAM_SIZE,                       # greedy = fast on CPU
            post_speech_silence_duration=SILENCE_SECS,
            silero_sensitivity=0.4,
            allowed_latency_limit=1000,                # don't drop audio if it briefly lags
            pre_recording_buffer_duration=1.0,         # keep 1s before speech so onsets aren't clipped
            min_gap_between_recordings=0.0,            # allow back-to-back lines (no missed continuation)
        )
        cbs = dict(on_recording_start=self._on_speech_start,
                   on_recording_stop=self._on_speech_stop)
        try:
            try:
                recorder = AudioToTextRecorder(**base, **cbs)
            except TypeError:
                recorder = AudioToTextRecorder(**base)
        except Exception as exc:
            self.ui_q.put(("error",
                           f"Couldn't start the microphone / model:\n{exc}\n\n"
                           "Check a mic is connected and not used by another app."))
            return

        self.recorder = recorder
        self.ui_q.put(("state", "listening"))
        try:
            while self.running:
                text = recorder.text()  # blocks until an utterance finalizes
                if not self.running:
                    break
                if text:
                    self.ui_q.put(("final", (self.current_speaker, text)))
                self.ui_q.put(("state", "listening"))  # re-arm banner
        except Exception as exc:
            self.ui_q.put(("error", f"Transcription stopped:\n{exc}"))
        finally:
            try:
                recorder.shutdown()
            except Exception:
                pass
            self.recorder = None

    # ---- UI pump (main thread) ----
    def drain_queue(self):
        try:
            while True:
                kind, data = self.ui_q.get_nowait()
                if kind == "final":
                    spk_val, line = data
                    self.append_text(spk_val, line)
                elif kind == "state":
                    if not self.paused:
                        self.set_state(str(data))
                elif kind == "msg":
                    self.msg_lbl.configure(text=str(data))
                elif kind == "error":
                    self.running = False
                    self.paused = False
                    self.btn.configure(text="●  Start")
                    self.pause_btn.configure(text="❚❚  Pause", state="disabled")
                    self.model_box.configure(state="readonly")
                    self.set_state("idle")
                    messagebox.showerror("Live Transcribe", str(data))
        except queue.Empty:
            pass
        self.root.after(60, self.drain_queue)

    def set_state(self, name: str):
        bg, fg, caption = STATES.get(name, STATES["idle"])
        self.banner.configure(bg=bg, fg=fg, text=caption)

    def append_text(self, spk_val: int, line: str):
        """Merge consecutive same-speaker speech into one paragraph; a speaker
        change starts a new paragraph."""
        line = line.strip()
        if not line:
            return
        self.text.configure(state="normal")
        if spk_val == self.last_block_speaker:
            # same speaker keeps talking -> extend the current paragraph
            self.text.insert("end", " " + line)
        else:
            # new speaker (or very first line) -> new paragraph with label + time
            if self.last_block_speaker is not None:
                self.text.insert("end", "\n\n")
            ts = dt.datetime.now().strftime("%H:%M:%S")
            self.text.insert("end", f"[{ts}] ", ("ts",))
            self.text.insert("end", f"{NAME.get(spk_val, '?')}: ", (f"spk{spk_val}",))
            self.text.insert("end", line)
            self.last_block_speaker = spk_val
        self.text.see("end")
        self.text.configure(state="disabled")

    def clear(self):
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.configure(state="disabled")
        self.last_block_speaker = None

    def save(self):
        path = filedialog.asksaveasfilename(
            defaultextension=".txt", filetypes=[("Text file", "*.txt")],
            initialfile=f"transcript_{dt.datetime.now():%Y%m%d_%H%M%S}.txt",
        )
        if not path:
            return
        self.text.configure(state="normal")
        data = self.text.get("1.0", "end").strip()
        self.text.configure(state="disabled")
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(data + "\n")
            self.msg_lbl.configure(text=f"Saved → {path}")
        except Exception as exc:
            messagebox.showerror("Live Transcribe", f"Couldn't save:\n{exc}")

    def on_close(self):
        self.running = False
        self._interrupt()
        r = self.recorder
        if r is not None:
            try:
                r.shutdown()
            except Exception:
                pass
        self.root.destroy()


def main():
    root = tk.Tk()
    try:
        ttk.Style().theme_use("vista")
    except Exception:
        pass
    LiveTranscribeApp(root)
    root.mainloop()


if __name__ == "__main__":
    multiprocessing.freeze_support()  # RealtimeSTT spawns a child process on Windows
    main()
