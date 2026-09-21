package dev.frost819.newbv.player.download

import java.util.concurrent.TimeUnit

/**
 * Empty buffers at startup/seek are expected, not evidence of a failed CDN. A required request
 * gets at least two seconds and its captured block-transfer estimate before readiness rescue.
 * The HTTP timeout still bounds pathological forecasts; pauses never justify a new rescue.
 */
internal object ReadinessRescuePolicy {
    fun observationComplete(
        elapsedNanos: Long,
        estimatedTransferNanos: Long?,
    ): Boolean = elapsedNanos >= maxOf(TimeUnit.SECONDS.toNanos(2), estimatedTransferNanos ?: 0)
}
