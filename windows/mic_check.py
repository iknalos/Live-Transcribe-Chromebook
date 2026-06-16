"""Mic diagnostic: lists input devices and measures your live mic level.
Run it, then SPEAK when it says to."""
import sounddevice as sd
import numpy as np

print("=== Input (recording) devices ===")
try:
    print(sd.query_devices())
except Exception as e:
    print("query_devices error:", e)

try:
    di = sd.query_devices(kind="input")
    print("\nDEFAULT INPUT DEVICE:", di["name"])
except Exception as e:
    print("\nNo usable default input device:", e)
    input("\nPress Enter to exit...")
    raise SystemExit(1)

fs = 16000
dur = 6
print(f"\n>>> Recording {dur} seconds. SPEAK NOW — loud and clear...\n")
try:
    rec = sd.rec(int(dur * fs), samplerate=fs, channels=1, dtype="float32")
    sd.wait()
except Exception as e:
    print("Recording failed:", e)
    input("\nPress Enter to exit...")
    raise SystemExit(1)

peak = float(np.max(np.abs(rec))) if rec.size else 0.0
rms = float(np.sqrt(np.mean(rec ** 2))) if rec.size else 0.0
bar = "#" * int(min(peak, 1.0) * 50)
print(f"Peak level: {peak:.4f}    RMS: {rms:.4f}")
print(f"[{bar:<50}]  (longer bar = louder)")

if peak < 0.01:
    print("\n>>> ALMOST NO SIGNAL. The mic is muted, the wrong device is set as")
    print("    default, or Windows is blocking microphone access for desktop apps.")
    print("    Fix: Settings > System > Sound > Input (pick the right mic, raise volume),")
    print("    and Settings > Privacy & security > Microphone (allow desktop apps).")
elif peak < 0.05:
    print("\n>>> VERY QUIET. The mic works but the level is low — raise the input")
    print("    volume or speak closer, then it should transcribe.")
else:
    print("\n>>> MIC IS GOOD. Audio is captured fine — the issue is app/VAD config,")
    print("    not your microphone. Tell Claude this result.")

input("\nPress Enter to exit...")
