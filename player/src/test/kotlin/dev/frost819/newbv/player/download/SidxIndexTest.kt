package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Verifies byte/time mapping comes from the index and malformed inputs are not visualized. */
class SidxIndexTest {
    @Test
    fun `SIDX maps byte offsets using box position first offset and timescale`() {
        val prefix = ByteBuffer.allocate(64)
        prefix.putInt(8).putInt(0x66747970)
        prefix.putInt(56).putInt(0x73696478)
        prefix.putInt(0).putInt(1).putInt(1000)
        prefix.putInt(5000).putInt(100)
        prefix.putShort(0).putShort(2)
        prefix.putInt(1000).putInt(2000).putInt(0)
        prefix.putInt(2000).putInt(3000).putInt(0)
        assertThat(SidxIndex.parse(prefix.array()))
            .containsExactly(
                IndexedSegment(164, 1163, 5000, 7000),
                IndexedSegment(1164, 3163, 7000, 10000),
            ).inOrder()
    }

    @Test
    fun `truncated boxes and nested references do not invent a timeline`() {
        assertThat(SidxIndex.parse(byteArrayOf(0, 1, 2))).isEmpty()
        val bytes = ByteBuffer.allocate(44)
        bytes.putInt(44).putInt(0x73696478)
        bytes.putInt(0).putInt(1).putInt(1000)
        bytes
            .putInt(0)
            .putInt(0)
            .putShort(0)
            .putShort(1)
        bytes.putInt(Int.MIN_VALUE or 100).putInt(2000).putInt(0)
        assertThat(SidxIndex.parse(bytes.array())).isEmpty()
        assertThat(SidxIndex.parse(bytes.array().copyOf(20))).isEmpty()
    }

    @Test
    fun `version one preserves offsets beyond four gigabytes`() {
        val bytes = ByteBuffer.allocate(52)
        bytes.putInt(52).putInt(0x73696478)
        bytes.putInt(0x01000000).putInt(1).putInt(1000)
        bytes
            .putLong(6000)
            .putLong(4294967296L)
            .putShort(0)
            .putShort(1)
        bytes.putInt(100).putInt(1000).putInt(0)
        assertThat(SidxIndex.parse(bytes.array())).containsExactly(
            IndexedSegment(4294967348L, 4294967447L, 6000, 7000),
        )
    }
}
