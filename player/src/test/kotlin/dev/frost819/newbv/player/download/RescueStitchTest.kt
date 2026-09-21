package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Suffix rescues retain a fixed prefix across concurrent writes and repeated handoffs. */
class RescueStitchTest {
    @Test
    fun `snapshot owns its bytes while original request continues writing`() {
        val base = byteArrayOf(1, 2)
        val body = byteArrayOf(3, 4, 0, 0)
        val frozen = RescueStitch.prefix(base, body, received = 2, totalLength = 6)
        body[2] = 5
        body[3] = 6
        base.fill(9)
        body[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3, 4), frozen)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), RescueStitch.join(frozen, byteArrayOf(5, 6), 6))
    }

    @Test
    fun `repeated rescue extends prior prefix without skipping or duplicating bytes`() {
        val original = byteArrayOf(0, 1, 2, 0, 0, 0, 0, 0)
        val first = RescueStitch.prefix(byteArrayOf(), original, received = 3, totalLength = 8)
        val rescueBody = byteArrayOf(3, 4, 0, 0, 0)
        val second = RescueStitch.prefix(first, rescueBody, received = 2, totalLength = 8)
        rescueBody[2] = 5
        rescueBody[3] = 6
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), second)
        assertContentEquals(ByteArray(8) { it.toByte() }, RescueStitch.join(second, byteArrayOf(5, 6, 7), 8))
        assertContentEquals(byteArrayOf(0, 1, 2), first)
    }

    @Test
    fun `invalid published count or incompatible total cannot create a prefix`() {
        assertFailsWith<IllegalArgumentException> { RescueStitch.prefix(byteArrayOf(), byteArrayOf(1), -1, 1) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.prefix(byteArrayOf(), byteArrayOf(1), 2, 2) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.prefix(byteArrayOf(1, 2), byteArrayOf(), 0, 1) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.prefix(byteArrayOf(1), byteArrayOf(2, 3), 2, 2) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.prefix(byteArrayOf(), byteArrayOf(), 0, -1) }
    }

    @Test
    fun `join rejects incomplete and oversized suffixes`() {
        assertFailsWith<IllegalArgumentException> { RescueStitch.join(byteArrayOf(1, 2), byteArrayOf(3), 4) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.join(byteArrayOf(1, 2), byteArrayOf(3, 4, 5), 4) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.join(byteArrayOf(1, 2), byteArrayOf(), 1) }
        assertFailsWith<IllegalArgumentException> { RescueStitch.join(byteArrayOf(), byteArrayOf(), -1) }
        assertContentEquals(byteArrayOf(1, 2), RescueStitch.join(byteArrayOf(1, 2), byteArrayOf(), 2))
        assertContentEquals(byteArrayOf(), RescueStitch.join(byteArrayOf(), byteArrayOf(), 0))
    }

    @Test
    fun `small suffix wait includes exactly sixty four KiB`() {
        assertTrue(RescueStitch.shouldWait(0))
        assertTrue(RescueStitch.shouldWait(65535))
        assertTrue(RescueStitch.shouldWait(65536))
        assertFalse(RescueStitch.shouldWait(65537))
        assertFalse(RescueStitch.shouldWait(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { RescueStitch.shouldWait(-1) }
    }
}
