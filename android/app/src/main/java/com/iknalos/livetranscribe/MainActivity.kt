package com.iknalos.livetranscribe

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Transcribe — real-time, speaker-tagged English transcription, fully offline.
 *
 * Two modes:
 *  • Manual — Android's on-device SpeechRecognizer; tap who is about to speak
 *    (buttons or keys 0–4). Proven, reliable for one mic passed around.
 *  • Auto (beta) — [SherpaEngine]: captures the mic itself, transcribes with
 *    offline Whisper, and labels speakers by voiceprint live. Enroll button lets
 *    a voice be registered with a name. Experimental; tune on-device.
 *
 * Both run 100% on-device. The app has no network permission at all.
 */
class MainActivity : AppCompatActivity() {

    private val spkNames = arrayOf("Me", "Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4")
    private val spkColors = intArrayOf(
        0xFF444444.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(),
        0xFFC62828.toInt(), 0xFF6A1B9A.toInt()
    )
    // colors for auto-discovered / enrolled speakers, indexed by speaker id
    private val autoPalette = intArrayOf(
        0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFF6A1B9A.toInt(),
        0xFF00838F.toInt(), 0xFFEF6C00.toInt(), 0xFF4527A0.toInt(), 0xFF558B2F.toInt(),
    )

    private val states = mapOf(
        "idle" to Triple(0xFFE0E0E0.toInt(), 0xFF444444.toInt(), "Idle — press Start to begin"),
        "loading" to Triple(0xFFFFE0B2.toInt(), 0xFF7A4F01.toInt(), "Loading…  (first time is slow)"),
        "listening" to Triple(0xFFC8E6C9.toInt(), 0xFF1B5E20.toInt(), "●  LISTENING — speak anytime"),
        "hearing" to Triple(0xFFFFCDD2.toInt(), 0xFFB71C1C.toInt(), "●  HEARING YOU…"),
        "transcribing" to Triple(0xFFFFE0B2.toInt(), 0xFF7A4F01.toInt(), "Transcribing…"),
        "paused" to Triple(0xFFFFF9C4.toInt(), 0xFF827717.toInt(), "❚❚  PAUSED — press Resume"),
        "stopped" to Triple(0xFFE0E0E0.toInt(), 0xFF444444.toInt(), "Stopped — press Start to resume"),
    )

    // key merges consecutive same-speaker paragraphs; label/color drive rendering
    private data class Para(val key: String, val label: String, val color: Int,
                            var text: String, val time: String)
    private val paras = ArrayList<Para>()

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnClear: Button
    private lateinit var btnSave: Button
    private lateinit var btnModeManual: Button
    private lateinit var btnModeAuto: Button
    private lateinit var btnEnroll: Button
    private lateinit var speakerRow: LinearLayout
    private lateinit var banner: TextView
    private lateinit var preview: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var footer: TextView
    private val spkButtons = ArrayList<Button>()

    private var autoMode = false
    private var engine: SherpaEngine? = null

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var currentSpeaker = 0
    private var utteranceSpeaker = 0       // speaker who owned the chunk being recorded
    private var hasPendingSpeech = false
    private var lastPartial = ""
    private val handler = Handler(Looper.getMainLooper())

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) start() else toast("Microphone permission is required.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnClear = findViewById(R.id.btnClear)
        btnSave = findViewById(R.id.btnSave)
        btnModeManual = findViewById(R.id.btnModeManual)
        btnModeAuto = findViewById(R.id.btnModeAuto)
        btnEnroll = findViewById(R.id.btnEnroll)
        speakerRow = findViewById(R.id.speakerRow)
        banner = findViewById(R.id.banner)
        preview = findViewById(R.id.preview)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        footer = findViewById(R.id.footer)

        btnStart.setOnClickListener { if (running) stop() else start() }
        btnPause.setOnClickListener { togglePause() }
        btnClear.setOnClickListener { clear() }
        btnSave.setOnClickListener { save() }
        btnModeManual.setOnClickListener { setMode(false) }
        btnModeAuto.setOnClickListener { setMode(true) }
        btnEnroll.setOnClickListener { promptEnroll() }

        buildSpeakerButtons()
        setMode(false)
        setState("idle")
    }

    // ---- mode ----
    private fun setMode(auto: Boolean) {
        if (running) { toast("Stop before switching mode."); return }
        autoMode = auto
        btnEnroll.isEnabled = false
        btnPause.isEnabled = false
        footer.text = if (auto)
            "Auto (offline, beta) — detects speakers by voice"
        else
            "Manual (offline) — tap who is speaking"
        fun tint(b: Button, on: Boolean) {
            b.backgroundTintList = ColorStateList.valueOf(
                if (on) 0xFF1565C0.toInt() else 0xFFE0E0E0.toInt())
            b.setTextColor(if (on) Color.WHITE else 0xFF333333.toInt())
        }
        tint(btnModeManual, !auto)
        tint(btnModeAuto, auto)
    }

    // ---- speaker selection / correction ----
    private fun buildSpeakerButtons() {
        for (i in spkNames.indices) {
            val b = Button(this).apply {
                text = "${spkNames[i]} [$i]"
                isAllCaps = false
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener { setSpeaker(i) }
            }
            speakerRow.addView(b)
            spkButtons.add(b)
        }
        highlightSpeaker()
    }

    private fun setSpeaker(i: Int) {
        if (autoMode) {
            currentSpeaker = i
            highlightSpeaker()
            if (running) relabelLast(i)   // buttons act as a correction in Auto mode
            return
        }
        val changed = i != currentSpeaker
        currentSpeaker = i
        highlightSpeaker()
        if (changed && running && !paused) {
            if (hasPendingSpeech) {
                try { recognizer?.stopListening() } catch (_: Exception) {}
            } else {
                utteranceSpeaker = i
            }
        }
    }

    /** Auto mode: reassign the most recent paragraph to a manual speaker. */
    private fun relabelLast(i: Int) {
        val idx = paras.lastIndex
        if (idx < 0) return
        paras[idx] = paras[idx].copy(key = "m$i", label = spkNames[i], color = spkColors[i])
        renderTranscript()
    }

    private fun highlightSpeaker() {
        for (i in spkButtons.indices) {
            val b = spkButtons[i]
            if (i == currentSpeaker) {
                b.backgroundTintList = ColorStateList.valueOf(spkColors[i])
                b.setTextColor(Color.WHITE)
            } else {
                b.backgroundTintList = ColorStateList.valueOf(0xFFE0E0E0.toInt())
                b.setTextColor(0xFF333333.toInt())
            }
        }
    }

    // ---- start / stop ----
    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (!hasMic()) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (autoMode) startAuto() else startManual()
    }

    private fun stop() {
        running = false
        paused = false
        val e = engine
        engine = null
        if (e != null) Thread { e.stop() }.start()   // join off the UI thread
        recognizer?.let {
            try { it.stopListening() } catch (_: Exception) {}
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
        btnStart.text = "● Start"
        btnPause.isEnabled = false
        btnEnroll.isEnabled = false
        btnModeManual.isEnabled = true
        btnModeAuto.isEnabled = true
        preview.text = ""
        setState("stopped")
    }

    // ---- Auto (sherpa-onnx, offline voiceprint) ----
    private fun startAuto() {
        running = true
        btnStart.text = "■ Stop"
        btnPause.isEnabled = false
        btnEnroll.isEnabled = true
        btnModeManual.isEnabled = false
        btnModeAuto.isEnabled = false
        footer.text = "Auto (offline) — detecting speakers by voice"
        setState("loading")
        val eng = SherpaEngine(this)
        engine = eng
        eng.start(object : SherpaEngine.Listener {
            override fun onText(text: String, speaker: SpeakerBook.Speaker) = runOnUiThread {
                appendFinal("a${speaker.id}", speaker.name, colorForId(speaker.id), text)
            }
            override fun onEnrolled(speaker: SpeakerBook.Speaker) = runOnUiThread {
                toast("Enrolled ${speaker.name}")
            }
            override fun onState(listening: Boolean) = runOnUiThread {
                if (running) setState(if (listening) "listening" else "loading")
            }
            override fun onError(msg: String) = runOnUiThread {
                toast(msg)
                stop()
            }
        })
    }

    private fun promptEnroll() {
        if (engine == null) { toast("Start Auto first, then enroll."); return }
        val input = EditText(this).apply { hint = "Name (e.g. Alex)" }
        AlertDialog.Builder(this)
            .setTitle("Enroll a voice")
            .setMessage("Type a name, tap OK, then have that person say a full sentence.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Speaker" }
                engine?.enrollNextUtterance(name)
                toast("Now have $name speak a sentence…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun colorForId(id: Int): Int =
        if (id <= 0) 0xFF757575.toInt() else autoPalette[(id - 1) % autoPalette.size]

    // ---- Manual (Android on-device recognizer) ----
    private fun startManual() {
        if (!onDeviceAvailable()) {
            setState("idle")
            footer.text = "On-device English not available — install it in Settings"
            showOnDeviceUnavailableDialog()
            return
        }
        running = true
        paused = false
        btnStart.text = "■ Stop"
        btnPause.isEnabled = true
        btnPause.text = "❚❚ Pause"
        btnModeManual.isEnabled = false
        btnModeAuto.isEnabled = false
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            .also { it.setRecognitionListener(listener) }
        footer.text = "Manual (offline) — audio stays on this device"
        setState("loading")
        listenAgain()
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun showOnDeviceUnavailableDialog() {
        AlertDialog.Builder(this)
            .setTitle("Offline recognition not available")
            .setMessage(
                "Manual mode uses Android's offline recognizer, so your audio never " +
                "leaves this device. Right now no offline English engine is installed.\n\n" +
                "Install \"English (US)\" on-device speech in Voice input / Languages " +
                "settings, then press Start — or use Auto (beta), which bundles its own " +
                "offline engine."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun recognizerIntent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            @Suppress("DEPRECATION")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun listenAgain() {
        if (!running || paused) return
        utteranceSpeaker = currentSpeaker
        hasPendingSpeech = false
        lastPartial = ""
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            handler.postDelayed({ listenAgain() }, 300)
        }
    }

    private fun togglePause() {
        if (!running || autoMode) return
        if (!paused) {
            paused = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            btnPause.text = "▶ Resume"
            preview.text = ""
            setState("paused")
        } else {
            paused = false
            btnPause.text = "❚❚ Pause"
            setState("listening")
            listenAgain()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (!paused) setState("listening") }
        override fun onBeginningOfSpeech() { hasPendingSpeech = true; setState("hearing") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setState("transcribing") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let {
                if (it.isNotBlank()) { lastPartial = it; hasPendingSpeech = true }
                preview.text = it
            }
        }

        override fun onResults(results: Bundle?) {
            val txt = firstResult(results)
            preview.text = ""
            if (!txt.isNullOrBlank()) appendManual(utteranceSpeaker, txt)
            hasPendingSpeech = false
            lastPartial = ""
            if (running && !paused) {
                setState("listening")
                listenAgain()
            }
        }

        override fun onError(error: Int) {
            preview.text = ""
            if (hasPendingSpeech && lastPartial.isNotBlank()) {
                appendManual(utteranceSpeaker, lastPartial)
            }
            hasPendingSpeech = false
            lastPartial = ""
            if (running && !paused) {
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 400L else 150L
                handler.postDelayed({ listenAgain() }, delay)
            }
        }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ---- transcript model ----
    private fun appendManual(spk: Int, raw: String) =
        appendFinal("m$spk", spkNames[spk], spkColors[spk], raw)

    private fun appendFinal(key: String, label: String, color: Int, raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val last = paras.lastOrNull()
        if (last != null && last.key == key) {
            last.text += " " + line
        } else {
            paras.add(Para(key, label, color, line, timeNow()))
        }
        renderTranscript()
    }

    private fun renderTranscript() {
        val sb = SpannableStringBuilder()
        for ((i, p) in paras.withIndex()) {
            if (i > 0) sb.append("\n\n")
            val tsStart = sb.length
            sb.append("[${p.time}] ")
            sb.setSpan(ForegroundColorSpan(0xFF999999.toInt()), tsStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val nameStart = sb.length
            sb.append("${p.label}: ")
            sb.setSpan(ForegroundColorSpan(p.color), nameStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), nameStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(p.text)
        }
        transcript.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clear() {
        paras.clear()
        renderTranscript()
    }

    private fun save() {
        val text = transcript.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Nothing to save yet.")
            return
        }
        val name = "transcript_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                toast("Couldn't create the file.")
                return
            }
            contentResolver.openOutputStream(uri)?.use { it.write((text + "\n").toByteArray()) }
            toast("Saved to Downloads / $name")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { setSpeaker(0); return true }
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { setSpeaker(1); return true }
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { setSpeaker(2); return true }
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { setSpeaker(3); return true }
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { setSpeaker(4); return true }
            KeyEvent.KEYCODE_M -> { setSpeaker(0); return true }
            KeyEvent.KEYCODE_SPACE -> { togglePause(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setState(name: String) {
        val (bg, fg, cap) = states[name] ?: states["idle"]!!
        banner.setBackgroundColor(bg)
        banner.setTextColor(fg)
        banner.text = cap
    }

    private fun timeNow() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        engine?.let { e -> Thread { e.stop() }.start() }
        engine = null
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
        super.onDestroy()
    }
}
