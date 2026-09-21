package dev.frost819.newbv.player.download

import android.app.ActivityManager
import android.content.Context

/** Current memory pressure, in bytes; Media3 reports allocator use, excluding decoder surfaces. */
data class DownloadMemorySnapshot(
    val heapUsedBytes: Long,
    val media3Bytes: Long,
    val deviceAvailableBytes: Long,
    val deviceTotalBytes: Long,
    val deviceThresholdBytes: Long,
    val deviceLowMemory: Boolean,
)

/** Lightweight pressure sampling; the session throttles calls independently of diagnostic logging. */
internal class DownloadMemoryReader(
    context: Context,
    private val media3Bytes: () -> Long,
) {
    private val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    fun sample(): DownloadMemorySnapshot? {
        val service = manager ?: return null
        return try {
            val info = ActivityManager.MemoryInfo().also(service::getMemoryInfo)
            val runtime = Runtime.getRuntime()
            DownloadMemorySnapshot(
                heapUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0),
                media3Bytes = media3Bytes().coerceAtLeast(0),
                deviceAvailableBytes = info.availMem.coerceAtLeast(0),
                deviceTotalBytes = info.totalMem.coerceAtLeast(0),
                deviceThresholdBytes = info.threshold.coerceAtLeast(0),
                deviceLowMemory = info.lowMemory,
            )
        } catch (_: RuntimeException) {
            null
        }
    }
}
