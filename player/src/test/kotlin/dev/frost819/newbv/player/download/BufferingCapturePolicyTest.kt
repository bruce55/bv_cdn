package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BufferingCapturePolicyTest {
    @Test
    fun `brief waits are ignored and sustained waits persist before recovery`() {
        val policy = BufferingCapturePolicy()
        assertNull(policy.sample(true, true, 100))
        assertNull(policy.sample(true, false, 2000))
        assertNull(policy.sample(true, true, 4000))
        assertNull(policy.sample(true, true, 6999))
        assertEquals(4000L, policy.sample(true, true, 7000))
        assertNull(policy.sample(true, true, 8000))
        assertEquals(4000L, policy.sample(true, true, 17000))
        assertEquals(4000L, policy.sample(true, false, 18000))
        assertNull(policy.sample(true, false, 19000))
    }

    @Test
    fun `disable resets incident rather than saving or joining later stalls`() {
        val policy = BufferingCapturePolicy()
        policy.sample(true, true, 0)
        assertEquals(0L, policy.sample(true, true, 3000))
        assertNull(policy.sample(false, true, 4000))
        assertNull(policy.sample(true, true, 9000))
        assertEquals(9000L, policy.sample(true, true, 12000))
    }
}
