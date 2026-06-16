package com.iknalos.livetranscribe

import kotlin.math.sqrt

/**
 * Tracks voiceprints and assigns each utterance to a speaker.
 *
 * - Auto-discovery: if an utterance's voiceprint doesn't match anyone closely
 *   enough (cosine >= [threshold]), a new "Speaker N" is created on the fly.
 * - Enrollment: a named voice can be registered up front; enrolled speakers are
 *   matched the same way but never have their centroid drifted, and carry names.
 *
 * threshold is the one dial that needs tuning on-device for a shared mic.
 */
class SpeakerBook(var threshold: Float = 0.55f) {

    data class Speaker(
        val id: Int,
        var name: String,
        var centroid: FloatArray,
        var count: Int,
        val enrolled: Boolean,
    )

    private val speakers = ArrayList<Speaker>()
    private var lastId = 0
    private var autoCount = 0
    private var unknown: Speaker? = null

    val all: List<Speaker> get() = speakers

    fun reset() {
        speakers.clear(); lastId = 0; autoCount = 0; unknown = null
    }

    /** Register a named voice from one or more samples. */
    fun enroll(name: String, embeddings: List<FloatArray>): Speaker {
        val c = normalize(mean(embeddings.map { normalize(it) }))
        val s = Speaker(++lastId, name, c, embeddings.size, enrolled = true)
        speakers.add(s)
        return s
    }

    /** Assign an utterance voiceprint to the closest speaker, or mint a new one. */
    fun assign(embedding: FloatArray): Speaker {
        val e = normalize(embedding)
        var best: Speaker? = null
        var bestSim = -1f
        for (s in speakers) {
            val sim = dot(e, s.centroid)
            if (sim > bestSim) { bestSim = sim; best = s }
        }
        if (best != null && bestSim >= threshold) {
            if (!best.enrolled) {            // adapt auto speakers toward the new sample
                val n = best.count + 1
                for (i in best.centroid.indices)
                    best.centroid[i] = (best.centroid[i] * best.count + e[i]) / n
                best.centroid = normalize(best.centroid)
                best.count = n
            }
            return best
        }
        autoCount++
        val s = Speaker(++lastId, "Speaker $autoCount", e.copyOf(), 1, enrolled = false)
        speakers.add(s)
        return s
    }

    /** Used when a voiceprint couldn't be computed but we still have text. */
    fun unknownSpeaker(): Speaker =
        unknown ?: Speaker(0, "Speaker ?", FloatArray(0), 0, enrolled = false).also { unknown = it }

    private fun normalize(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        var n = 0f; for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return -1f
        var s = 0f; val m = minOf(a.size, b.size)
        for (i in 0 until m) s += a[i] * b[i]
        return s
    }

    private fun mean(vs: List<FloatArray>): FloatArray {
        val out = FloatArray(vs[0].size)
        for (v in vs) for (i in out.indices) out[i] += v[i]
        for (i in out.indices) out[i] /= vs.size
        return out
    }
}
