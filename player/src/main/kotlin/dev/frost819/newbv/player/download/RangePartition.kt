package dev.frost819.newbv.player.download

/** Balanced subdivision using the original extension's worker-count policy. */
internal object RangePartition {
    const val TARGET_BYTES = 64 * 1024L
    const val MIN_EXPLORATION_BYTES = 2 * 1024 * 1024L
    const val UNKNOWN_INDEX_BYTES = 1024 * 1024L

    fun split(
        start: Long,
        endInclusive: Long,
        concurrency: Int,
        minimumBytes: Long = 0,
    ): List<LongRange> {
        require(start >= 0 && endInclusive >= start && endInclusive < Long.MAX_VALUE)
        val length = endInclusive - start + 1
        val count =
            minOf(
                concurrency.coerceIn(1, 512).toLong(),
                if (minimumBytes > 0) (length / minimumBytes).coerceAtLeast(1) else (length - 1) / TARGET_BYTES + 1,
            ).toInt()
        val base = length / count
        val remainder = length % count
        var cursor = start
        return List(count) { index ->
            val size = base + if (index < remainder) 1 else 0
            (cursor..cursor + size - 1).also { cursor += size }
        }
    }

    fun pieceBudget(
        limit: Int,
        startup: Boolean,
        track: DownloadTrack,
    ): Int {
        if (!startup) return (limit - if (limit >= 8) 1 else 0).coerceAtLeast(1)
        val reserve = if (limit >= 3) 1 else 0
        return if (track == DownloadTrack.Audio) 1 else (limit - reserve - 1).coerceAtLeast(1)
    }
}
