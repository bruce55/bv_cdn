package dev.frost819.newbv.player.download

/**
 * Shared partition policy for one playback session. Buffer allowances total at most half the
 * actual VM heap; the remainder covers UI, networking, metadata, temporary copies and GC headroom.
 * These are allocation targets, not an exact cap on all live objects or native decoder memory.
 */
internal class PlaybackBufferBudget(
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
) {
    init {
        require(maxHeapBytes > 0)
    }

    val media3Bytes = minOf(maxHeapBytes / 4, 128 * MIB).toInt()
    val payloadBytes = minOf(maxHeapBytes / 8, 128 * MIB)
    val rescueBytes = minOf(maxHeapBytes / 16, 64 * MIB)
    val cacheBytes = minOf(maxHeapBytes / 16, 32 * MIB)
    val totalBytes = media3Bytes + payloadBytes + rescueBytes + cacheBytes

    private companion object {
        const val MIB = 1024 * 1024L
    }
}
