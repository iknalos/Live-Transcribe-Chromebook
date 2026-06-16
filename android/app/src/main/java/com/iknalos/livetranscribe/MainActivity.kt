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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Transcribe — real-time, speaker-tagged English transcription for
 * Chromebooks / Android. Mirrors the desktop tool: tap who is about to speak
 * (buttons or number keys 0–4) as you hand the mic over; consecutive speech
 * from the same speaker merges into one paragraph, a speaker change starts a
 * new one. Pause/Resume mutes without tearing down the recognizer.
 *
 * Engine: Android's on-device SpeechRecognizer where available (API 33+),
 * otherwise the system recognizer with EXTRA_PREFER_OFFLINE. On-device keeps
 * audio on the device once the English language pack is installed.
 */
class MainActivity : AppCompatActivity() {

    private val spkNames = arrayOf("Me", "Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4")
    private val spkColors = intArrayOf(
        0xFF444444.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(),
        0xFFC62828.toInt(), 0xFF6A1B9A.toInt()
    )

    // banner state -> (background, foreground, caption)
    private val states = mapOf(
        "idle" to Triple(0xFFE0E0E0.toInt(), 0xFF444444.toInt(), "Idle — press Start to begin"),
        "loading" to Triple(0xFFFFE0B2.toInt(), 0xFF7A4F01.toInt(), "Loading recognizer…"),
        "listening" to Triple(0xFFC8E6C9.toInt(), 0xFF1B5E20.toInt(), "●  LISTENING — speak anytime"),
        "hearing" to Triple(0xFFFFCDD2.toInt(), 0xFFB71C1C.toInt(), "●  HEARING YOU…"),
        "transcribing" to Triple(0xFFFFE0B2.toInt(), 0xFF7A4F01.toInt(), "Transcribing…"),
        "paused" to Triple(0xFFFFF9C4.toInt(), 0xFF827717.toInt(), "❚❚  PAUSED — press Resume"),
        "stopped" to Triple(0xFFE0E0E0.toInt(), 0xFF444444.toInt(), "Stopped — press Start to resume"),
    )

    private data class Para(val speaker: Int, var text: String, val time: String)
    private val paras = ArrayList<Para>()

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnClear: Button
    private lateinit var btnSave: Button
    private lateinit var speakerRow: LinearLayout
    private lateinit var banner: TextView
    private lateinit var preview: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var footer: TextView
    private val spkButtons = ArrayList<Button>()

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var onDevice = false
    private var currentSpeaker = 0
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

        buildSpeakerButtons()
        setState("idle")
    }

    // ---- speaker selection ----
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
        currentSpeaker = i
        highlightSpeaker()
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognition service is available on this device.")
            return
        }
        running = true
        paused = false
        btnStart.text = "■ Stop"
        btnPause.isEnabled = true
        btnPause.text = "❚❚ Pause"
        recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
        footer.text = if (onDevice)
            "On-device recognition — audio stays on this device"
        else
            "Using the system recognizer (may use the network)"
        setState("loading")
        listenAgain()
    }

    private fun createRecognizer(): SpeechRecognizer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            onDevice = false
            SpeechRecognizer.createSpeechRecognizer(this)
        }
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
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            handler.postDelayed({ listenAgain() }, 300)
        }
    }

    private fun stop() {
        running = false
        paused = false
        recognizer?.let {
            try { it.stopListening() } catch (_: Exception) {}
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
        btnStart.text = "● Start"
        btnPause.isEnabled = false
        btnPause.text = "❚❚ Pause"
        preview.text = ""
        setState("stopped")
    }

    private fun togglePause() {
        if (!running) return
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

    // ---- recognition callbacks ----
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (!paused) setState("listening") }
        override fun onBeginningOfSpeech() { setState("hearing") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setState("transcribing") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { preview.text = it }
        }

        override fun onResults(results: Bundle?) {
            val txt = firstResult(results)
            preview.text = ""
            if (!txt.isNullOrBlank()) appendFinal(currentSpeaker, txt)
            if (running && !paused) {
                setState("listening")
                listenAgain()
            }
        }

        override fun onError(error: Int) {
            preview.text = ""
            // No-match / timeout / busy are normal during continuous listening:
            // just arm the next utterance.
            if (running && !paused) {
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 400L else 150L
                handler.postDelayed({ listenAgain() }, delay)
            }
        }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ---- transcript model ----
    private fun appendFinal(spk: Int, raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val last = paras.lastOrNull()
        if (last != null && last.speaker == spk) {
            last.text += " " + line
        } else {
            paras.add(Para(spk, line, timeNow()))
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
            sb.append("${spkNames[p.speaker]}: ")
            sb.setSpan(ForegroundColorSpan(spkColors[p.speaker]), nameStart, sb.length,
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

    // ---- hardware-keyboard shortcuts (Chromebook) ----
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
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
        super.onDestroy()
    }
}
