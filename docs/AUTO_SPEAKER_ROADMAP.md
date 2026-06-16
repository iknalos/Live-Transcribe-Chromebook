# Live offline automatic speaker labeling — roadmap

Goal: in addition to the proven **manual** tagging, add an **Auto** mode that
detects speakers **live and fully offline** by matching voiceprints, plus a
**Quick Enroll** button so each person can register their voice for better
accuracy. The user wants both auto-discovery *and* enrollment to A/B test which
is better. App size up to ~1 GB is acceptable.

## Why this needs a new engine
Manual mode uses Android's built-in `SpeechRecognizer`, which **owns the mic and
exposes no raw audio**, so we can't compute voiceprints from it. Auto mode
therefore runs an independent, fully on-device pipeline via **sherpa-onnx**:

```
AudioRecord (16 kHz mono PCM)
  → Silero VAD (segment speech into utterances)
  → per utterance:
       OfflineRecognizer (Whisper base.en)  → words
       SpeakerEmbeddingExtractor            → voiceprint (FloatArray)
  → SpeakerBook: cosine-match voiceprint to known speakers
       match ≥ threshold → that speaker;  else → new "Speaker N"
       (enrolled voiceprints take priority and carry names)
```

Manual mode is **left untouched** (Google on-device recognizer) so the working
tool always remains available. The two modes never run at once → no mic contention.

## Engine integration (sherpa-onnx, no official Maven artifact)
Confirmed approach (see k2-fsa/sherpa-onnx Android docs):
- **Native libs**: `libonnxruntime.so` (~15 MB) + `libsherpa-onnx-jni.so` in
  `app/src/main/jniLibs/arm64-v8a/`. Loaded via `System.loadLibrary("sherpa-onnx-jni")`.
- **Kotlin API**: vendor every `.kt` from `sherpa-onnx/kotlin-api/` (package
  `com.k2fsa.sherpa.onnx`) at a pinned version.
- **Models** in `app/src/main/assets/`: `silero_vad.onnx`, Whisper base.en
  (encoder/decoder int8 + tokens), a speaker-embedding model (3D-Speaker/WeSpeaker).
- **CI fetches all three** (libs, API, models) at build time so the repo stays
  lean; `.gitignore` excludes them. Since the app has **no INTERNET permission**,
  models must be bundled (can't download at runtime) → big APK, as expected.
- `build.gradle`: `ndk { abiFilters 'arm64-v8a' }` (phones; keeps size sane).

## Key API (grounded from upstream examples)
- VAD: `Vad(assetManager, VadModelConfig(sileroVad=SileroVadModelConfig(...), sampleRate=16000))`,
  `vad.acceptWaveform(floats)`, `while(!vad.empty()){ vad.front(); vad.pop() }`.
- ASR: `OfflineRecognizer(assetManager, OfflineRecognizerConfig(featConfig, modelConfig))`,
  `val s = rec.createStream(); s.acceptWaveform(samples, 16000); rec.decode(s); rec.getResult(s).text`.
- Speaker: `SpeakerEmbeddingExtractor(...)` → embedding `FloatArray`;
  `SpeakerEmbeddingManager(dim)` `.add(name, emb)` / `.search(emb, threshold)`.

## Stages (each ships an APK to test on-device)
1. **Engine swap**: Auto mode transcribes live & offline via sherpa (manual
   tagging still). Proves native libs + VAD + ASR work on the phone.
2. **Auto-discovery**: add voiceprints + `SpeakerBook` incremental clustering →
   live "Speaker N" labels with zero setup. Manual buttons override/correct.
3. **Quick Enroll**: button to register a named voice sample; enrolled prints
   win matches. A/B against auto-discovery.

## Verify (on device)
- Airplane mode ON → Auto mode still transcribes (proves offline).
- App info → permissions shows microphone only (no network).
- Speak as 2–3 people; confirm labels track speaker changes; tune match threshold.

Version: ships as **v2.x** (major change). Experimental — expect threshold/model
tuning across a few on-device test rounds.
