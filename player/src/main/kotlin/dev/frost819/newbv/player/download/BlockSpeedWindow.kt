package dev.frost819.newbv.player.download

import kotlin.math.pow

/**
 * Finalized per-block rates for one CDN/representation, weighted by media position rather than arrival.
 * Keeps at most 64 samples within 60 media seconds of the furthest completed block T, with a
 * 15-second half-life. Each rate includes the entire request duration, including response latency.
 * Caller owns synchronization. Wall-clock idle time never deletes a usable fallback estimate.
 */
internal class BlockSpeedWindow {
    private data class Sample(
        val mediaTimeMs: Long,
        val bytes: Int,
        val nanos: Long,
    ) {
        val speed: Double get() = bytes.toDouble() / nanos
    }

    private val samples = mutableListOf<Sample>()
    private val bootstrap = java.util.ArrayDeque<Double>()
    private var fallback: Double? = null
    private var transitionWeight = 0.0

    /** Furthest completed indexed media position in this epoch; unindexed probes never advance it. */
    var furthestMediaTimeMs: Long? = null
        private set

    /** Retained indexed samples, bounded independently of playback duration and request count. */
    val sampleCount: Int get() = samples.size

    /** Captures the current usable estimate, then starts a fresh media-position epoch after a seek. */
    fun beginSeek() {
        fallback = estimate()
        samples.clear()
        bootstrap.clear()
        furthestMediaTimeMs = null
        transitionWeight = 0.0
    }

    /** Adds one validated request, or a compact bootstrap rate when media position is unknown. */
    fun add(
        bytes: Int,
        nanos: Long,
        mediaTimeMs: Long?,
    ) {
        if (bytes <= 0 || nanos <= 0) return
        if (mediaTimeMs == null || mediaTimeMs < 0) {
            // Seek fallbacks remain stable until indexed data supplies meaningful new evidence.
            if (fallback == null && samples.isEmpty()) {
                bootstrap.addLast(bytes.toDouble() / nanos)
                while (bootstrap.size > 8) bootstrap.removeFirst()
            }
            return
        }
        val frontier = maxOf(furthestMediaTimeMs ?: mediaTimeMs, mediaTimeMs)
        furthestMediaTimeMs = frontier
        samples.removeAll { frontier - it.mediaTimeMs > WINDOW_MS }
        if (frontier - mediaTimeMs > WINDOW_MS) return
        samples.add(Sample(mediaTimeMs, bytes, nanos))
        samples.sortBy { it.mediaTimeMs }
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        transitionWeight = newEvidenceWeight()
        if (transitionWeight >= 1.0) {
            // Once the new epoch is established, window eviction cannot resurrect old evidence.
            fallback = null
            bootstrap.clear()
        }
    }

    /**
     * Fresh data is credible after 256 KiB or one second of finalized transfer duration. The
     * fallback then blends out up to full replacement at 1 MiB or four seconds. Sample count
     * alone cannot promote tiny probes. Thresholds count only retained samples in this epoch.
     */
    fun newEvidenceWeight(): Double {
        if (samples.isEmpty()) return 0.0
        if (fallbackEstimate() == null) return 1.0
        val bytes = samples.sumOf { it.bytes.toLong() }
        val duration = samples.sumOf { it.nanos.toDouble() }
        if (bytes < 256 * 1024L && duration < 1_000_000_000.0) return transitionWeight
        return maxOf(transitionWeight, bytes / (1024 * 1024.0), duration / 4_000_000_000.0).coerceAtMost(1.0)
    }

    /** Weighted mean of individual block speeds in bytes/ns, optionally blended with seek/bootstrap fallback. */
    fun estimate(): Double? {
        val old = fallbackEstimate()
        val frontier = furthestMediaTimeMs ?: return old
        if (samples.isEmpty()) return old
        var weighted = 0.0
        var weights = 0.0
        for (sample in samples) {
            val weight = 2.0.pow(-(frontier - sample.mediaTimeMs).toDouble() / HALF_LIFE_MS)
            weighted += sample.speed * weight
            weights += weight
        }
        val fresh = weighted / weights
        if (old == null) return fresh
        val weight = newEvidenceWeight()
        return old * (1.0 - weight) + fresh * weight
    }

    private fun fallbackEstimate(): Double? = fallback ?: bootstrap.takeIf { it.isNotEmpty() }?.average()

    private companion object {
        const val MAX_SAMPLES = 64
        const val WINDOW_MS = 60_000L
        const val HALF_LIFE_MS = 15_000.0
    }
}
