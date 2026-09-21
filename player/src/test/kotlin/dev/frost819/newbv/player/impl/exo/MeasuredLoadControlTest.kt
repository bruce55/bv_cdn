package dev.frost819.newbv.player.impl.exo

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import dev.frost819.newbv.player.download.PlaybackBufferBudget
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeasuredLoadControlTest {
    @Test
    fun `byte budget stops loading before duration goal and resumes after consumption`() {
        val control = MeasuredLoadControl(PlaybackBufferBudget(4 * 1024 * 1024L))
        val playerId = mockk<PlayerId>()
        val parameters =
            LoadControl.Parameters(
                playerId,
                Timeline.EMPTY,
                MediaPeriodId(Any()),
                0,
                5_000_000,
                1f,
                true,
                false,
                C.TIME_UNSET,
                C.TIME_UNSET,
            )
        control.onPrepared(playerId)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
        assertEquals(1024 * 1024, control.memoryFields()["selectedTargetBytes"])
        assertTrue(control.shouldContinueLoading(parameters))
        val blocks = List(16) { control.allocator.allocate() }
        assertFalse(control.shouldContinueLoading(parameters))
        control.allocator.release(blocks.first())
        assertTrue(control.shouldContinueLoading(parameters))
        blocks.drop(1).forEach { control.allocator.release(it) }
        control.onReleased(playerId)
    }

    @Test
    fun `allocator reports assigned sample bytes and excludes released pool`() {
        val control = MeasuredLoadControl()
        val first = control.allocator.allocate()
        val second = control.allocator.allocate()
        assertEquals(2 * C.DEFAULT_BUFFER_SEGMENT_SIZE, control.memoryFields()["allocatorInUseBytes"])
        control.allocator.release(first)
        assertEquals(C.DEFAULT_BUFFER_SEGMENT_SIZE, control.memoryFields()["allocatorInUseBytes"])
        control.allocator.release(second)
        assertEquals(0, control.memoryFields()["allocatorInUseBytes"])
        assertEquals(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, control.memoryFields()["maximumBufferMs"])
        assertEquals(DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS, control.memoryFields()["backBufferMs"])
    }
}
