package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadAheadAdmissionTest {
    private fun candidate(
        id: Long,
        prepared: Long,
        pending: Int = 1,
        urgent: Boolean = false,
    ) = ReadAheadAdmission.Candidate(id, prepared, 0, prepared, pending, urgent)

    @Test
    fun `prepared data does not introduce a second count or time limit`() {
        val video = candidate(1, 60000, pending = 64)
        assertEquals(video, ReadAheadAdmission.choose(listOf(video)))
    }

    @Test
    fun `each admission favors behind track including scheduled future horizon`() {
        val video = candidate(1, 3000)
        val audio = candidate(2, 6000)
        assertEquals(video, ReadAheadAdmission.choose(listOf(audio, video)))
        val advancedVideo = candidate(1, 7000)
        assertEquals(audio, ReadAheadAdmission.choose(listOf(audio, advancedVideo)))
    }

    @Test
    fun `urgent demand precedes track balancing`() {
        val urgent = candidate(2, 60000, pending = 0, urgent = true)
        assertEquals(urgent, ReadAheadAdmission.choose(listOf(candidate(1, 0), urgent)))
    }

    @Test
    fun `available track continues when other has no work`() {
        val audio = candidate(2, 90000)
        assertEquals(audio, ReadAheadAdmission.choose(listOf(audio)))
        assertNull(ReadAheadAdmission.choose(emptyList()))
    }

    @Test
    fun `denied video does not admit far future audio into remaining memory`() {
        val video = candidate(1, 185000)
        val audio = candidate(2, 757343, pending = 110)
        assertNull(ReadAheadAdmission.choose(listOf(video, audio), setOf(video.key)))
        assertEquals(video, ReadAheadAdmission.choose(listOf(video, audio)))
    }

    @Test
    fun `immediate demand can bypass denied peer without waiting for balance`() {
        val video = candidate(1, 10000, urgent = true)
        val audio = candidate(2, 15000, pending = 0, urgent = true)
        assertEquals(audio, ReadAheadAdmission.choose(listOf(video, audio), setOf(video.key)))
    }

    @Test
    fun `peer can catch up to blocked horizon but cannot continue beyond it`() {
        val video = candidate(1, 10000)
        val audio = candidate(2, 10000)
        assertEquals(audio, ReadAheadAdmission.choose(listOf(video, audio), setOf(video.key)))
        assertNull(ReadAheadAdmission.choose(listOf(video, audio.copy(preparedTimeMs = 11000)), setOf(video.key)))
        assertNull(ReadAheadAdmission.choose(listOf(video, audio), setOf(video.key, audio.key)))
    }
}
