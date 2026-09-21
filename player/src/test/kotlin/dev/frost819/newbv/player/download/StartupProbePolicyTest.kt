package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Startup slots follow validated success counts, without stealing initial track allocations. */
class StartupProbePolicyTest {
    private val audio = DownloadTrack.Audio
    private val video = DownloadTrack.Video

    @Test
    fun `initial allocation preserves audio slots before audio registers then replenishes audio`() {
        val policy = StartupProbePolicy(8, setOf(audio, video))
        policy.register(video, 20)
        repeat(5) { assertTrue(policy.tryAcquire(video)) }
        assertFalse(policy.tryAcquire(video))
        policy.completed(video, true)
        assertFalse(policy.tryAcquire(video))
        policy.register(audio, 20)
        repeat(2) { assertTrue(policy.tryAcquire(audio)) }
        assertTrue(policy.tryAcquire(audio))
        assertFalse(policy.tryAcquire(audio))
        repeat(3) {
            policy.completed(audio, true)
            assertFalse(policy.tryAcquire(video))
            assertTrue(policy.tryAcquire(audio))
        }
        assertEquals(3, policy.validCount(audio))
    }

    @Test
    fun `failed requests do not advance audio success priority and rollback retains candidate`() {
        val policy = StartupProbePolicy(4, setOf(audio, video))
        policy.register(audio, 2)
        policy.register(video, 10)
        assertTrue(policy.tryAcquire(audio))
        policy.abandon(audio)
        assertTrue(policy.tryAcquire(audio))
        repeat(2) { assertTrue(policy.tryAcquire(video)) }
        policy.completed(audio, false)
        assertFalse(policy.tryAcquire(video))
        assertTrue(policy.tryAcquire(audio))
        policy.completed(audio, true)
        assertTrue(policy.isTrackDone(audio))
        assertTrue(policy.tryAcquire(video))
    }

    @Test
    fun `random track choice persists until acquired after half audio successes`() {
        val policy = StartupProbePolicy(4, setOf(audio, video), Random(6))
        policy.register(audio, 20)
        policy.register(video, 20)
        assertTrue(policy.tryAcquire(audio))
        repeat(2) { assertTrue(policy.tryAcquire(video)) }
        repeat(2) {
            policy.completed(audio, true)
            if (it == 0) assertTrue(policy.tryAcquire(audio))
        }
        val nextAudio = policy.tryAcquire(audio)
        if (!nextAudio) {
            repeat(20) { assertFalse(policy.tryAcquire(audio)) }
            assertTrue(policy.tryAcquire(video))
        }
        assertEquals(3, policy.inFlightCount(audio) + policy.inFlightCount(video))
    }

    @Test
    fun `target stops a track with requests outstanding and closed peer does not hang`() {
        val policy = StartupProbePolicy(4, setOf(audio, video))
        policy.register(audio, 20)
        policy.endTrack(video)
        repeat(3) { assertTrue(policy.tryAcquire(audio)) }
        repeat(3) {
            policy.completed(audio, true)
            assertTrue(policy.tryAcquire(audio))
        }
        policy.completed(audio, true)
        assertEquals(4, policy.validCount(audio))
        assertTrue(policy.isTrackDone(audio))
        assertFalse(policy.tryAcquire(audio))
        assertTrue(policy.isFinished())
        repeat(2) { policy.completed(audio, false) }
        assertEquals(0, policy.inFlightCount(audio))
    }

    @Test
    fun `single track and zero candidates finish without reserving absent tracks`() {
        val policy = StartupProbePolicy(8, setOf(video))
        policy.register(video, 1)
        assertTrue(policy.tryAcquire(video))
        assertFalse(policy.isFinished())
        policy.completed(video, true)
        assertTrue(policy.isFinished())
        val empty = StartupProbePolicy(8, setOf(audio, video))
        empty.register(audio, 0)
        empty.register(video, 0)
        assertTrue(empty.isFinished())
    }
}
