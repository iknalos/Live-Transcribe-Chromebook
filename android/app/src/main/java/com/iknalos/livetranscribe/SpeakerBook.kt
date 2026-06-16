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
class SpeakerBook(var threshold: Float = 0.45f) {

    // How much closer another voice must be before we switch away from the
    // speaker we just assigned — prevents label flicker between similar voices.
    var stickyMargin = 0.06f

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
    private var lastAssignedId = -1
    private var unknown: Speaker? = null

    val all: List<Speaker> get() = speakers

    fun reset() {
        speakers.clear(); lastId = 0; autoCount = 0; lastAssignedId = -1; unknown = null
    }

    /** Register a named voice from one or more samples. */
    fun enroll(name: String, embeddings: List<FloatArray>): Speaker {
        val c = normalize(mean(embeddings.map { normalize(it) }))
        val s = Speaker(++lastId, name, c, embeddings.size, enrolled = true)
        speakers.add(s)
        return s
    }

    /**
     * Assign an utterance voiceprint to the closest speaker, or mint a new one.
     * [canCreateNew] is false for short/uncertain clips — those stick with the
     * last speaker instead of spawning a spurious new one.
     */
    fun assign(embedding: FloatArray, canCreateNew: Boolean = true): Speaker {
        val e = normalize(embedding)
        var best: Speaker? = null
        var bestSim = -1f
        var lastSpk: Speaker? = null
        var lastSim = -1f
        for (s in speakers) {
            val sim = dot(e, s.centroid)
            if (sim > bestSim) { bestSim = sim; best = s }
            if (s.id == lastAssignedId) { lastSpk = s; lastSim = sim }
        }
        // Stickiness: keep the previous speaker if they're nearly as close.
        if (lastSpk != null && bestSim - lastSim <= stickyMargin) {
            best = lastSpk; bestSim = lastSim
        }
        if (best != null && bestSim >= threshold) {
            adapt(best, e)
            lastAssignedId = best.id
            return best
        }
        if (!canCreateNew) {
            val fallback = lastSpk ?: best ?: unknownSpeaker()
            lastAssignedId = fallback.id
            return fallback
        }
        autoCount++
        val s = Speaker(++lastId, "Speaker $autoCount", e.copyOf(), 1, enrolled = false)
        speakers.add(s)
        lastAssignedId = s.id
        return s
    }

    private fun adapt(s: Speaker, e: FloatArray) {
        if (s.enrolled) return            // keep enrolled references pristine
        val n = s.count + 1
        for (i in s.centroid.indices)
            s.centroid[i] = (s.centroid[i] * s.count + e[i]) / n
        s.centroid = normalize(s.centroid)
        s.count = n
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
        for (v in vs) for (i in out.indices) out[i] = out[i] + v[i]
        val k = vs.size.toFloat()
        for (i in out.indices) out[i] = out[i] / k
        return out
    }
}
