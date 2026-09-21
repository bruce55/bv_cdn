package dev.frost819.newbv.player.download

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playback-owned process/device memory telemetry. All expensive sampling runs on one IO coroutine,
 * at most once every five seconds while [enabled]; trim callbacks only record their event level.
 * Categories are Android's accounted process memory, not exact GPU or video-decoder allocations.
 * [close] releases callbacks and cancels only this sampler, leaving the caller's scope alive.
 */
internal class PlaybackMemorySampler(
    context: Context,
    scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val record: (type: String, fields: Map<String, Any?>) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    collect: (() -> Map<String, Any?>)? = null,
    collectPreviousExit: (() -> Map<String, Any?>?)? = null,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val collectMemory = collect ?: { collectPlaybackMemory(applicationContext) }
    private val collectExit = collectPreviousExit ?: { collectPreviousProcessExit(applicationContext) }
    private val closed = AtomicBoolean(false)
    private val callbacks =
        object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                emit("memory_trim", mapOf("callback" to "low_memory"))
            }

            override fun onTrimMemory(level: Int) {
                emit("memory_trim", mapOf("callback" to "trim_memory", "level" to level))
            }
        }
    private val registered =
        try {
            applicationContext.registerComponentCallbacks(callbacks)
            true
        } catch (_: RuntimeException) {
            false
        }
    private val job =
        scope.launch(dispatcher) {
            var previousExitCollected = false
            while (isActive && !closed.get()) {
                if (enabled()) {
                    try {
                        emit("memory_sample", collectMemory())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        emit("memory_sample", mapOf("collectionError" to error.javaClass.simpleName))
                    }
                    if (!previousExitCollected) {
                        previousExitCollected = true
                        try {
                            collectExit()?.let { emit("memory_exit_history", it) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: RuntimeException) {
                            emit("memory_exit_history", mapOf("collectionError" to error.javaClass.simpleName))
                        }
                    }
                }
                // Delay after collection: a slow platform call cannot enqueue or overlap samples.
                delay(5_000)
            }
        }

    private fun emit(
        type: String,
        fields: Map<String, Any?>,
    ) {
        if (closed.get() || !enabled()) return
        try {
            record(type, fields)
        } catch (_: RuntimeException) {
            // Telemetry failures must not affect player or framework callback lifecycles.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        if (registered) {
            try {
                applicationContext.unregisterComponentCallbacks(callbacks)
            } catch (_: RuntimeException) {
                // The application may already be tearing down callbacks.
            }
        }
    }
}

private fun collectPlaybackMemory(context: Context): Map<String, Any?> {
    val runtime = Runtime.getRuntime()
    val process = Debug.MemoryInfo()
    Debug.getMemoryInfo(process)
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val device = activityManager?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }
    val fields =
        linkedMapOf<String, Any?>(
            "pid" to Process.myPid(),
            "runtime" to
                mapOf(
                    "usedBytes" to (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0),
                    "committedBytes" to runtime.totalMemory(),
                    "maxBytes" to runtime.maxMemory(),
                ),
            "process" to
                mapOf(
                    "pssBytes" to memoryKiBToBytes(process.totalPss.toLong()),
                    "privateDirtyBytes" to memoryKiBToBytes(process.totalPrivateDirty.toLong()),
                    "sharedDirtyBytes" to memoryKiBToBytes(process.totalSharedDirty.toLong()),
                    "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
                    "nativeHeapSizeBytes" to Debug.getNativeHeapSize(),
                    "nativeHeapFreeBytes" to Debug.getNativeHeapFreeSize(),
                ),
        )
    if (device != null) {
        fields["device"] =
            mapOf(
                "totalBytes" to device.totalMem,
                "availableBytes" to device.availMem,
                "lowMemoryThresholdBytes" to device.threshold,
                "lowMemory" to device.lowMemory,
                "memoryClassMiB" to activityManager?.memoryClass,
                "largeMemoryClassMiB" to activityManager?.largeMemoryClass,
                "lowRamDevice" to activityManager?.isLowRamDevice,
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
            )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val categories = linkedMapOf<String, Long>()
        for ((key, name) in MEMORY_CATEGORIES) {
            process.getMemoryStat(key)?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                categories[name] = memoryKiBToBytes(it)
            }
        }
        fields["accountedCategories"] = categories
    }
    return fields
}

private fun collectPreviousProcessExit(context: Context): Map<String, Any?>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val exit = manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return null
    return mapOf(
        "pid" to exit.pid,
        "timestampMs" to exit.timestamp,
        "reason" to exit.reason,
        "reasonName" to
            when (exit.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_SIGNALED -> "signal"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                ApplicationExitInfo.REASON_EXIT_SELF -> "self_exit"
                ApplicationExitInfo.REASON_OTHER -> "other"
                else -> "unknown_or_other_reason"
            },
        "status" to exit.status,
        "importance" to exit.importance,
        "pssBytes" to memoryKiBToBytes(exit.pss),
        "rssBytes" to memoryKiBToBytes(exit.rss),
    )
}

/** Platform process-memory counters are KiB; unavailable negative readings become zero. */
internal fun memoryKiBToBytes(kib: Long): Long = kib.coerceIn(0, Long.MAX_VALUE / 1024) * 1024

private val MEMORY_CATEGORIES =
    listOf(
        "summary.java-heap" to "javaHeapBytes",
        "summary.native-heap" to "nativeHeapBytes",
        "summary.code" to "codeBytes",
        "summary.stack" to "stackBytes",
        "summary.graphics" to "graphicsBytes",
        "summary.private-other" to "privateOtherBytes",
        "summary.system" to "systemBytes",
    )
