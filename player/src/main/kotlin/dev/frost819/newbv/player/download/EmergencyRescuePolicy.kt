package dev.frost819.newbv.player.download

/** Bounds additional replicas for a playback-blocking range after its first rescue has stalled. */
internal object EmergencyRescuePolicy {
    private const val OBSERVATION_NANOS = 2_000_000_000L
    private const val LAUNCH_INTERVAL_NANOS = 1_000_000_000L

    /** Includes the original attempt, retaining one rescue even with a two- or three-slot pool. */
    fun attemptLimit(maxRequests: Int): Int {
        val total = maxRequests.coerceIn(1, 64)
        return (total / 2).coerceAtLeast(2).coerceAtMost(total)
    }

    /** Adds replicas progressively before the deadline, leaving the backup time to finish. */
    fun shouldRampBeforeDeadline(
        playing: Boolean,
        blockingRange: Boolean,
        allAttemptsAtRisk: Boolean,
        activeAttempts: Int,
        youngestAttemptAgeNanos: Long,
        timeSinceLastLaunchNanos: Long,
        deadlineSlackNanos: Long?,
        backupTransferNanos: Long?,
        maxRequests: Int,
    ): Boolean {
        val cap = attemptLimit(maxRequests)
        if (!playing ||
            !blockingRange ||
            !allAttemptsAtRisk ||
            activeAttempts !in 1 until cap ||
            youngestAttemptAgeNanos < OBSERVATION_NANOS ||
            timeSinceLastLaunchNanos < OBSERVATION_NANOS ||
            deadlineSlackNanos == null ||
            backupTransferNanos == null ||
            deadlineSlackNanos <= 0
        ) {
            return false
        }
        // Higher concurrency cannot push speculative rescue arbitrarily far ahead.
        val ramp = ((cap - activeAttempts - 1).coerceAtLeast(0) * OBSERVATION_NANOS).coerceAtMost(6_000_000_000L)
        return deadlineSlackNanos <= backupTransferNanos + OBSERVATION_NANOS + ramp
    }

    /**
     * Allows one additional replica only during actual rebuffering of the blocking range.
     * The original and rescues share the half-pool cap, with a two-attempt minimum bounded by the pool.
     * Every newest attempt gets a burst-tolerant observation period before another launch.
     */
    fun shouldLaunch(
        actualRebuffering: Boolean,
        blockingRange: Boolean,
        activeAttempts: Int,
        rescueAlreadyStarted: Boolean,
        youngestAttemptAgeNanos: Long,
        timeSinceLastLaunchNanos: Long,
        maxRequests: Int,
    ): Boolean {
        val cap = attemptLimit(maxRequests)
        return actualRebuffering &&
            blockingRange &&
            rescueAlreadyStarted &&
            activeAttempts in 1 until cap &&
            youngestAttemptAgeNanos >= OBSERVATION_NANOS &&
            timeSinceLastLaunchNanos >= LAUNCH_INTERVAL_NANOS
    }
}
