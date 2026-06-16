# Live Transcribe — multi-platform

Real-time, **speaker-tagged** English transcription, built for interviewing a small
group with **one mic passed around**: tag who is about to speak (buttons or number
keys `0–4`) as you hand the mic over. Consecutive speech from the same speaker merges
into one paragraph; switching speakers starts a new one. The same idea, three ways:

| Platform | Folder | Engine | Offline? |
|---|---|---|---|
| **Android** (phone / Chromebook) | [`android/`](android) | On-device `SpeechRecognizer` (Manual) + bundled sherpa-onnx (Auto, voiceprint speaker ID) | ✅ fully on-device, no network permission |
| **Windows** (desktop) | [`windows/`](windows) | Whisper via RealtimeSTT (Python) | ✅ after first model download |
| **Web** (Chrome PWA) | [`web/`](web) | Browser Web Speech API | ❌ Chrome streams audio to Google |

The **Android** app builds into a downloadable APK via GitHub Actions — grab it from
the [Releases page](../../releases). The **Windows** app is the original Python
desktop tool (run `windows/Live Transcribe.bat`). All share the same UX: a big
color-coded status banner, live preview, Save to `.txt`, Clear, and keyboard
shortcuts (`0`–`4` to tag a speaker, `m` for "Me", `space` to pause).

---

## Android app (best for Chromebooks)

Chromebooks run Android apps, and the Android version uses Google's **on-device**
speech recognizer — so after the English language pack downloads once, transcription
stays on the device (no audio leaves it). This is the closest match to the offline
desktop tool.

**Two modes (both 100% offline):**
- **Manual** — Android's on-device recognizer; tap who's speaking (keys 0–4).
  Proven and reliable for one mic passed around.
- **Auto (beta)** — a bundled sherpa-onnx engine captures the mic, transcribes
  with offline Whisper, and **labels speakers by voiceprint live**. It
  auto-discovers "Speaker 1/2/…" as new voices appear, and the **Enroll** button
  lets you register a named voice for better accuracy. The speaker buttons act as
  a correction in Auto mode. Experimental — accuracy on a shared mic varies and
  the match threshold may need tuning. This is why the APK is large (it bundles
  the offline speech + speaker models). See
  [`docs/AUTO_SPEAKER_ROADMAP.md`](docs/AUTO_SPEAKER_ROADMAP.md).

**On-device only — no silent cloud fallback.** The app uses *only* the offline
engine. If your device doesn't have offline English installed, it refuses to run
and points you to Settings instead of quietly streaming audio to Google. The APK
is small (~11 MB) because it bundles no speech model — the offline model is
provided by Android itself and downloaded separately via Settings.

**The app has no network access at all.** It declares only the microphone
permission, and the manifest explicitly *strips* `INTERNET` (and network-state)
at build time, so the app process physically cannot open a network connection.
The build pipeline even fails if a future change ever reintroduces a network
permission — see the "Verify APK has no network permission" step in
[`.github/workflows/build.yml`](.github/workflows/build.yml). You can confirm
this yourself: the APK's permission list (e.g. in your phone's App info, or
`aapt dump permissions`) shows microphone only.

> Note on scope: speech recognition itself runs in Android's *separate*
> on-device engine (a different process), not inside this app. On-device mode
> processes audio locally, and this app can't talk to the network — but the
> definitive end-to-end test is to **turn on airplane mode and confirm
> transcription still works.** It will.

### Install the prebuilt APK
1. Every push to `main` builds a signed APK via GitHub Actions and attaches it to a
   **Release**. Grab the latest from the [Releases page](../../releases).
2. On the Chromebook, enable **Play Store / Android apps** (Settings → Apps), then
   allow installing unknown apps and open the downloaded `LiveTranscribe.apk`.
3. First run: grant the microphone permission. If recognition says it needs a
   language pack, install **English (US)** offline speech in Android settings
   (Settings → System → Languages & input → On-device recognition / Voice).

### Build it yourself
Open the `android/` folder in Android Studio and Run, or from a machine with the
Android SDK:
```bash
cd android
gradle assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Chrome web version (PWA)

Zero install — open the page in Chrome. It can also be **installed** as an app
(Chrome → ⋮ → "Install Live Transcribe") so it gets its own window and launcher icon.

> ⚠️ The browser Web Speech API sends audio to Google's servers, so the web version
> is **not** offline. Use the Android app if you need on-device/private transcription.

Serve `web/` over HTTPS (the mic and PWA install both require a secure context). The
easiest path is **GitHub Pages**: repo Settings → Pages → deploy from `main` /`docs`
or point it at the `web/` folder, then open the published URL.

To try it locally:
```bash
cd web
python -m http.server 8000
# open http://localhost:8000  (localhost counts as a secure context)
```

---

Built by iknalos. Companion to the offline desktop Live Transcribe tool.
