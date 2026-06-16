package com.iknalos.livetranscribe

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Fully on-device Auto-mode engine. Captures the mic itself (AudioRecord),
 * segments speech with Silero VAD, transcribes each segment with offline Whisper
 * (base.en), and computes a speaker voiceprint per segment so [SpeakerBook] can
 * label it live. Nothing touches the network. Runs on its own thread.
 *
 * Models live in assets/ (fetched by CI): silero_vad.onnx, base.en-*.onnx,
 * base.en-tokens.txt, speaker.onnx.
 */
class SherpaEngine(private val context: Context) {

    interface Listener {
        fun onText(text: String, speaker: SpeakerBook.Speaker)
        fun onEnrolled(speaker: SpeakerBook.Speaker)
        fun onState(listening: Boolean)
        fun onError(msg: String)
    }

    val book = SpeakerBook()

    private val sampleRate = 16000
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var running = false
    @Volatile private var enrollName: String? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Capture the next spoken utterance as an enrollment sample for [name]. */
    fun enrollNextUtterance(name: String) { enrollName = name }

    fun start(listener: Listener) {
        if (running) return
        thread = Thread { run(listener) }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(2000) } catch (_: Exception) {}
        thread = null
    }

    private fun run(listener: Listener) {
        try {
            val assets = context.assets
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                    ),
                    sampleRate = sampleRate,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            recognizer = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "base.en-encoder.int8.onnx",
                            decoder = "base.en-decoder.int8.onnx",
                        ),
                        tokens = "base.en-tokens.txt",
                        numThreads = 2,
                        modelType = "whisper",
                    ),
                ),
            )
            extractor = SpeakerEmbeddingExtractor(
                assetManager = assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = "speaker.onnx",
                    numThreads = 2,
                    provider = "cpu",
                ),
            )
        } catch (e: Throwable) {
            listener.onError("Couldn't load the offline models: ${e.message}")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2)
            )
        } catch (e: SecurityException) {
            listener.onError("Microphone permission denied."); return
        }

        running = true
        val buffer = ShortArray(512)
        try {
            rec.startRecording()
            listener.onState(true)
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                val v = vad ?: break
                v.acceptWaveform(samples)
                while (!v.empty()) {
                    val seg = v.front()
                    v.pop()
                    if (!running) break
                    handleSegment(seg.samples, listener)
                }
            }
        } catch (e: Throwable) {
            if (running) listener.onError("Auto engine stopped: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            try { vad?.release() } catch (_: Exception) {}
            try { recognizer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            vad = null; recognizer = null; extractor = null
            listener.onState(false)
        }
    }

    private fun handleSegment(samples: FloatArray, listener: Listener) {
        // Enrollment: consume this utterance as a named voice sample, no transcript.
        val name = enrollName
        if (name != null) {
            enrollName = null
            val emb = embed(samples)
            if (emb != null) listener.onEnrolled(book.enroll(name, listOf(emb)))
            else listener.onError("Didn't catch that voice — tap Enroll and try again.")
            return
        }

        val rec = recognizer ?: return
        val text = try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val t = rec.getResult(stream).text.trim()
            stream.release()
            t
        } catch (e: Throwable) { "" }
        if (text.isEmpty()) return

        val emb = embed(samples)
        val speaker = if (emb != null) book.assign(emb) else book.unknownSpeaker()
        listener.onText(text, speaker)
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val ex = extractor ?: return null
        return try {
            val s = ex.createStream()
            s.acceptWaveform(samples, sampleRate)
            s.inputFinished()
            val emb = if (ex.isReady(s)) ex.compute(s) else null
            s.release()
            emb
        } catch (e: Throwable) { null }
    }
}
