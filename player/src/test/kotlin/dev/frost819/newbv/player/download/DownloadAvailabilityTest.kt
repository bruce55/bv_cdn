package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Availability follows resident bytes and the current player buffer, independently of event history. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAvailabilityTest {
    @Test
    fun `standard bar receives combined buffer with visualization and diagnostics disabled`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(enabled = true), backgroundScope)
            val bytes = index()
            val base = bytes.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, bytes)
            var cached = listOf(base + 200 until base + 300)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) { mapOf(DownloadTrack.Video to cached) }
            monitor.updatePlayback(500, 1f, true, true, false, bufferedPositionMs = 1500)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.bufferedRanges)
                .containsExactly(
                    DownloadBufferedRange(500, 1500),
                    DownloadBufferedRange(2000, 3000),
                ).inOrder()
            cached = emptyList()
            monitor.updatePlayback(600, 1f, true, true, false, bufferedPositionMs = 1500)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.bufferedRanges).containsExactly(DownloadBufferedRange(600, 1500))
            monitor.clearPlayerBuffer()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.bufferedRanges).isEmpty()
            monitor.close()
        }

    @Test
    fun `cache and player buffer form a union without painting holes or historical completions`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = index()
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            monitor.setAvailabilitySampler(DownloadTrack.entries.toSet()) {
                mapOf(DownloadTrack.Video to listOf(base until base + 100, base + 200 until base + 300))
            }
            val history = monitor.plan(DownloadTrack.Video, base + 300, base + 399)
            monitor.state(history, DownloadBlockState.Complete)
            monitor.updatePlayback(500, 1f, true, true, false, bufferedPositionMs = 1500)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 0, 1500),
                DownloadAvailableRange(DownloadTrack.Video, 2000, 3000),
                DownloadAvailableRange(DownloadTrack.Audio, 500, 1500),
            )
            assertThat(
                monitor.snapshots.value.blocks
                    .single()
                    .state,
            ).isEqualTo(DownloadBlockState.Complete)
            monitor.close()
        }

    @Test
    fun `eviction removes only cached availability and seek replaces the old player buffer`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = index()
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            var resident = listOf(base until base + 100)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) { mapOf(DownloadTrack.Video to resident) }
            monitor.updatePlayback(2000, 1f, false, false, false, bufferedPositionMs = 3000, isReady = true)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).hasSize(2)
            resident = emptyList()
            monitor.updatePlayback(2000, 1f, false, false, false, bufferedPositionMs = 3000, isReady = true)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 2000, 3000),
            )
            monitor.clearPlayerBuffer()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).isEmpty()
            monitor.updatePlayback(500, 1f, false, false, true, bufferedPositionMs = 800)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 500, 800),
            )
            monitor.close()
            assertThat(monitor.snapshots.value.availableRanges).isEmpty()
        }

    @Test
    fun `unknown index never guesses cache times while valid player buffer needs no index`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Audio)) {
                mapOf(DownloadTrack.Audio to listOf(0L..1000L))
            }
            monitor.updatePlayback(100, 1f, false, false, false, bufferedPositionMs = 900, retainedBufferValid = false)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).isEmpty()
            monitor.updatePlayback(100, 1f, false, false, false, bufferedPositionMs = 900, isReady = true)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Audio, 100, 900),
            )
            monitor.close()
        }

    @Test
    fun `delivered audio lead stays green after downloader release and clips played data`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = index()
            val base = index.size.toLong()
            DownloadTrack.entries.forEach { monitor.recordBytes(it, 0, index) }
            var local = mapOf(DownloadTrack.Audio to listOf(base until base + 300))
            monitor.setAvailabilitySampler(DownloadTrack.entries.toSet()) { local }
            val audioEpoch = monitor.beginRead(DownloadTrack.Audio, base)
            monitor.consumedBytes(DownloadTrack.Audio, base, 300, audioEpoch)
            local = emptyMap()
            monitor.updatePlayback(500, 1f, true, true, false, bufferedPositionMs = 1000)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 500, 1000),
                DownloadAvailableRange(DownloadTrack.Audio, 500, 3000),
            )
            monitor.updatePlayback(1500, 1f, true, true, false, bufferedPositionMs = 2000)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 1500, 2000),
                DownloadAvailableRange(DownloadTrack.Audio, 1500, 3000),
            )
            monitor.close()
        }

    @Test
    fun `seek discards delivered estimates rejects late old reader data and preserves local cache`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = index()
            val base = index.size.toLong()
            DownloadTrack.entries.forEach { monitor.recordBytes(it, 0, index) }
            monitor.setAvailabilitySampler(DownloadTrack.entries.toSet()) {
                mapOf(DownloadTrack.Video to listOf(base + 200 until base + 300))
            }
            monitor.routeProgress("media.example", 400)
            val oldEpoch = monitor.beginRead(DownloadTrack.Audio, base)
            monitor.consumedBytes(DownloadTrack.Audio, base, 300, oldEpoch)
            monitor.clearPlayerBuffer()
            monitor.consumedBytes(DownloadTrack.Audio, base + 300, 100, oldEpoch)
            monitor.updatePlayback(2500, 1f, false, false, true, bufferedPositionMs = 2600)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 2000, 3000),
                DownloadAvailableRange(DownloadTrack.Audio, 2500, 2600),
            )
            assertThat(monitor.snapshots.value.transferStats.usedBytes).isEqualTo(400L)
            val newEpoch = monitor.beginRead(DownloadTrack.Audio, base + 200)
            monitor.consumedBytes(DownloadTrack.Audio, base + 300, 100, oldEpoch)
            monitor.consumedBytes(DownloadTrack.Audio, base + 200, 100, newEpoch)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 2000, 3000),
                DownloadAvailableRange(DownloadTrack.Audio, 2500, 3000),
            )
            monitor.close()
        }

    @Test
    fun `reader replacement clears only its track while invalid Media3 buffer clears both estimates`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = index()
            val base = index.size.toLong()
            DownloadTrack.entries.forEach { monitor.recordBytes(it, 0, index) }
            monitor.setAvailabilitySampler(DownloadTrack.entries.toSet()) { emptyMap() }
            for (track in DownloadTrack.entries) {
                val epoch = monitor.beginRead(track, base)
                monitor.consumedBytes(track, base, 300, epoch)
            }
            monitor.updatePlayback(500, 1f, true, true, false, bufferedPositionMs = 1000)
            monitor.beginRead(DownloadTrack.Video, base + 200)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).containsExactly(
                DownloadAvailableRange(DownloadTrack.Video, 500, 1000),
                DownloadAvailableRange(DownloadTrack.Audio, 500, 3000),
            )
            monitor.updatePlayback(500, 1f, false, false, false, bufferedPositionMs = 1000, retainedBufferValid = false)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.availableRanges).isEmpty()
            monitor.close()
        }

    private fun index(): ByteArray =
        ByteBuffer
            .allocate(88)
            .apply {
                putInt(88)
                    .putInt(0x73696478)
                    .putInt(0x01000000)
                    .putInt(1)
                    .putInt(1000)
                putLong(0).putLong(0).putShort(0).putShort(4)
                repeat(4) { putInt(100).putInt(1000).putInt(0) }
            }.array()
}
