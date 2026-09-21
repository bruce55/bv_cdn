package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emergency replicas must remain bounded and tolerate burst gaps before escalating. */
class EmergencyRescuePolicyTest {
    private fun allowed(
        limit: Int = 8,
        active: Int = 2,
        rebuffering: Boolean = true,
        blocking: Boolean = true,
        rescued: Boolean = true,
        youngestAge: Long = 2_000_000_000L,
        sinceLaunch: Long = 2_000_000_000L,
    ): Boolean =
        EmergencyRescuePolicy.shouldLaunch(
            actualRebuffering = rebuffering,
            blockingRange = blocking,
            activeAttempts = active,
            rescueAlreadyStarted = rescued,
            youngestAttemptAgeNanos = youngestAge,
            timeSinceLastLaunchNanos = sinceLaunch,
            maxRequests = limit,
        )

    @Test
    fun `predeadline ramp increases replicas as backup slack runs out`() {
        fun ramp(
            active: Int,
            slackSeconds: Long,
            risk: Boolean = true,
            age: Long = 2_000_000_000L,
        ) = EmergencyRescuePolicy.shouldRampBeforeDeadline(
            playing = true,
            blockingRange = true,
            allAttemptsAtRisk = risk,
            activeAttempts = active,
            youngestAttemptAgeNanos = age,
            timeSinceLastLaunchNanos = age,
            deadlineSlackNanos = slackSeconds * 1_000_000_000L,
            backupTransferNanos = 2_000_000_000L,
            maxRequests = 8,
        )
        assertFalse(ramp(1, 9))
        assertTrue(ramp(1, 8))
        assertFalse(ramp(2, 7))
        assertTrue(ramp(2, 6))
        assertFalse(ramp(3, 5))
        assertTrue(ramp(3, 4))
        assertFalse(ramp(4, 1))
        assertFalse(ramp(2, 4, risk = false))
        assertFalse(ramp(2, 4, age = 1_999_999_999L))
        assertFalse(ramp(2, 0))
    }

    @Test
    fun `predeadline ramp requires playback a required range and known backup timing`() {
        fun ramp(
            playing: Boolean = true,
            blocking: Boolean = true,
            backup: Long? = 1_000_000_000L,
        ) = EmergencyRescuePolicy.shouldRampBeforeDeadline(
            playing,
            blocking,
            true,
            2,
            2_000_000_000L,
            2_000_000_000L,
            3_000_000_000L,
            backup,
            8,
        )
        assertTrue(ramp())
        assertFalse(ramp(playing = false))
        assertFalse(ramp(blocking = false))
        assertFalse(ramp(backup = null))
    }

    @Test
    fun `original and rescues share the half pool cap with one rescue in small pools`() {
        mapOf(1 to 1, 2 to 2, 3 to 2, 4 to 2, 8 to 4, 64 to 32).forEach { (limit, cap) ->
            assertEquals(cap, EmergencyRescuePolicy.attemptLimit(limit))
            if (cap > 1) assertTrue(allowed(limit = limit, active = cap - 1))
            assertFalse(allowed(limit = limit, active = cap))
            assertFalse(allowed(limit = limit, active = cap + 1))
        }
        assertFalse(allowed(active = 0))
    }

    @Test
    fun `predeadline and buffering escalation use the same cap and observation period`() {
        mapOf(2 to 2, 3 to 2, 8 to 4, 64 to 32).forEach { (limit, cap) ->
            fun ramp(
                active: Int,
                age: Long = 2_000_000_000L,
                sinceLaunch: Long = 2_000_000_000L,
            ) = EmergencyRescuePolicy.shouldRampBeforeDeadline(
                playing = true,
                blockingRange = true,
                allAttemptsAtRisk = true,
                activeAttempts = active,
                youngestAttemptAgeNanos = age,
                timeSinceLastLaunchNanos = sinceLaunch,
                deadlineSlackNanos = 1_000_000_000L,
                backupTransferNanos = 2_000_000_000L,
                maxRequests = limit,
            )
            assertTrue(ramp(cap - 1))
            assertFalse(ramp(cap))
            assertFalse(ramp(cap - 1, age = 1_999_999_999L))
            assertFalse(ramp(cap - 1, sinceLaunch = 1_999_999_999L))
            assertFalse(allowed(limit = limit, active = cap - 1, youngestAge = 1_999_999_999L))
        }
    }

    @Test
    fun `startup pause and nonblocking downloads cannot trigger emergency escalation`() {
        assertFalse(allowed(rebuffering = false))
        assertFalse(allowed(blocking = false))
        assertFalse(allowed(rescued = false))
        assertTrue(allowed())
    }

    @Test
    fun `newest rescue receives two seconds to produce bursty data before escalation`() {
        assertFalse(allowed(youngestAge = 1_999_999_999L))
        assertFalse(allowed(youngestAge = 0))
        assertTrue(allowed(youngestAge = 2_000_000_000L))
        assertFalse(allowed(sinceLaunch = 999_999_999L))
        assertTrue(allowed(sinceLaunch = 1_000_000_000L))
    }
}
