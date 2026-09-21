package dev.frost819.newbv.player.download

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Memory sampling remains opt-in, serialized, rate-limited and detached from the player lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackMemorySamplerTest {
    @Test
    fun `initial sample is immediate then gated to five seconds and close unregisters once`() =
        runTest {
            val context = applicationContext()
            val events = mutableListOf<String>()
            var collections = 0
            val sampler =
                PlaybackMemorySampler(
                    context,
                    backgroundScope,
                    enabled = { true },
                    record = { type, _ -> events.add(type) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    collect = {
                        collections++
                        mapOf("usedBytes" to 123L)
                    },
                    collectPreviousExit = { mapOf("reason" to 3) },
                )
            runCurrent()
            assertThat(collections).isEqualTo(1)
            assertThat(events).containsExactly("memory_sample", "memory_exit_history").inOrder()
            advanceTimeBy(4999)
            runCurrent()
            assertThat(collections).isEqualTo(1)
            advanceTimeBy(1)
            runCurrent()
            assertThat(collections).isEqualTo(2)
            assertThat(events.count { it == "memory_exit_history" }).isEqualTo(1)
            sampler.close()
            sampler.close()
            advanceTimeBy(10_000)
            runCurrent()
            assertThat(collections).isEqualTo(2)
            verify(exactly = 1) { context.unregisterComponentCallbacks(any()) }
        }

    @Test
    fun `disabled collection stays cheap and trim events never invoke heavy collector`() =
        runTest {
            val context = applicationContext()
            val callback = slot<ComponentCallbacks>()
            every { context.registerComponentCallbacks(capture(callback)) } returns Unit
            var enabled = false
            var collections = 0
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            val sampler =
                PlaybackMemorySampler(
                    context,
                    backgroundScope,
                    enabled = { enabled },
                    record = { type, fields -> events.add(type to fields) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    collect = {
                        collections++
                        emptyMap()
                    },
                    collectPreviousExit = { null },
                )
            runCurrent()
            val callbacks = callback.captured as ComponentCallbacks2
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertThat(collections).isEqualTo(0)
            assertThat(events).isEmpty()
            enabled = true
            callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertThat(events.single().first).isEqualTo("memory_trim")
            assertThat(events.single().second["level"]).isEqualTo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertThat(collections).isEqualTo(0)
            advanceTimeBy(5000)
            runCurrent()
            assertThat(collections).isEqualTo(1)
            sampler.close()
            callbacks.onLowMemory()
            assertThat(events).hasSize(2)
        }

    @Test
    fun `collection and recorder failures do not terminate future sampling`() =
        runTest {
            var attempts = 0
            val sampler =
                PlaybackMemorySampler(
                    applicationContext(),
                    backgroundScope,
                    enabled = { true },
                    record = { _, _ -> throw IllegalStateException("Recorder unavailable") },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    collect = {
                        attempts++
                        if (attempts == 1) throw IllegalStateException("Platform unavailable")
                        emptyMap()
                    },
                    collectPreviousExit = { null },
                )
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            assertThat(attempts).isEqualTo(2)
            sampler.close()
        }

    @Test
    fun `KiB conversion preserves bytes without wrapping long counters`() {
        assertThat(memoryKiBToBytes(123)).isEqualTo(125_952L)
        assertThat(memoryKiBToBytes(-1)).isEqualTo(0L)
        assertThat(memoryKiBToBytes(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE / 1024 * 1024)
    }

    private fun applicationContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return context
    }
}
