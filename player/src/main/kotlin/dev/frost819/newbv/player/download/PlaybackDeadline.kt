package dev.frost819.newbv.player.download

/** Immutable playback sample shared with network workers; all timestamps use System.nanoTime. */
internal data class PlaybackDeadline(
    val positionMs: Long,
    val speed: Float,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val sampledAtNanos: Long,
) {
    /** Computes a conservative segment deadline without advancing a stalled playback clock. */
    fun forSegment(
        segmentStartMs: Long,
        nowNanos: Long = System.nanoTime(),
    ): Long? {
        if (!playWhenReady || (!isPlaying && !isBuffering) || !speed.isFinite() || speed <= 0) return null
        val untilNeededMs = (segmentStartMs.toDouble() - positionMs.coerceAtLeast(0)) / speed - SAFETY_MARGIN_MS
        // During buffering the position is stationary; only the currently needed segment is urgent.
        val anchor = if (isPlaying) sampledAtNanos else nowNanos
        val offset = (untilNeededMs * 1_000_000).coerceIn(-MAX_OFFSET_NANOS, MAX_OFFSET_NANOS).toLong()
        return anchor + offset
    }

    private companion object {
        const val SAFETY_MARGIN_MS = 750
        const val MAX_OFFSET_NANOS = 86_400_000_000_000.0
    }
}
