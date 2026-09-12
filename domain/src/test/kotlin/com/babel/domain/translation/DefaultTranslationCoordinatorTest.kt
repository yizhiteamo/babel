package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRuntimeState
import com.babel.core.testing.FakeSettingsRepository
import com.babel.core.testing.FakeSystemLocaleProvider
import com.babel.core.testing.FakeTranslationCache
import com.babel.core.testing.FakeTranslator
import com.babel.core.testing.RecordingLogger
import com.babel.core.testing.RecordingRenderer
import com.babel.core.testing.TestDispatcherProvider
import com.babel.core.testing.TestElements
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.language.DefaultLanguageResolver
import com.babel.domain.privacy.DefaultSensitiveContentPolicy
import com.babel.domain.render.RenderUpdate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTranslationCoordinatorTest {

    private val translator = FakeTranslator()
    private val cache = FakeTranslationCache()
    private val settings = FakeSettingsRepository()
    private val systemLocale = FakeSystemLocaleProvider(LanguageTag("zh"))
    private val renderer = RecordingRenderer()
    private val logger = RecordingLogger()

    private fun TestScope.newCoordinator(
        config: CoordinatorConfig = CoordinatorConfig(),
    ): DefaultTranslationCoordinator = DefaultTranslationCoordinator(
        translator = translator,
        cache = cache,
        languageResolver = DefaultLanguageResolver(
            systemLocaleProvider = systemLocale,
            supported = listOf("en", "zh", "ja").map(::LanguageTag),
        ),
        settingsRepository = settings,
        sensitivePolicy = DefaultSensitiveContentPolicy(),
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = logger,
        config = config,
    )

    /**
     * Starts the coordinator with the renderer already listening.
     *
     * The collector runs on [UnconfinedTestDispatcher] so it receives each
     * emission at the point it is made. A `StandardTestDispatcher` collector in
     * `backgroundScope` is never resumed by `advanceUntilIdle`, and every
     * assertion about rendering would silently see an empty renderer.
     */
    private fun TestScope.start(
        config: CoordinatorConfig = CoordinatorConfig(),
    ): DefaultTranslationCoordinator {
        val coordinator = newCoordinator(config)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.renderUpdates.collect { renderer.apply(it) }
        }
        coordinator.start()
        advanceUntilIdle()
        return coordinator
    }

    @Test
    fun `translates visible text and renders it`() = runTest {
        val coordinator = start()

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(listOf("<Hello>"), renderer.visibleText)
        assertEquals(TranslationRuntimeState.Running, coordinator.runtimeState.value)
        coordinator.stop()
    }

    @Test
    fun `old result never overwrites newer visible content`() = runTest {
        translator.delayMillis = 1_000
        val coordinator = start()

        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(text = "old", revision = 0))),
        )
        advanceTimeBy(500) // still in flight

        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(text = "new", revision = 1))),
        )
        advanceUntilIdle()

        assertEquals(listOf("<new>"), renderer.visibleText)
        assertTrue(translator.cancelled.any { it.sourceText == "old" })
        coordinator.stop()
    }

    @Test
    fun `scrolling moves the overlay without re-translating`() = runTest {
        val coordinator = start()

        coordinator.submit(
            TextSourceEvent.Upserted(
                listOf(TestElements.element(text = "Hello", bounds = TestElements.bounds(top = 0, bottom = 48))),
            ),
        )
        advanceUntilIdle()
        val callsAfterFirstPass = translator.callCount

        coordinator.submit(
            TextSourceEvent.Upserted(
                listOf(
                    TestElements.element(
                        text = "Hello",
                        revision = 1,
                        bounds = TestElements.bounds(top = 300, bottom = 348),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(callsAfterFirstPass, translator.callCount)
        assertEquals(300, renderer.visible.getValue(TextElementId("e1")).bounds.top)
        coordinator.stop()
    }

    @Test
    fun `repeated identical events do not cause repeated requests`() = runTest {
        val coordinator = start()
        val element = TestElements.element(text = "Hello")

        repeat(5) {
            coordinator.submit(TextSourceEvent.Upserted(listOf(element)))
            advanceUntilIdle()
        }

        assertEquals(1, translator.callCount)
        coordinator.stop()
    }

    @Test
    fun `removed elements hide their overlay`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        coordinator.submit(TextSourceEvent.Removed(listOf(TextElementId("e1"))))
        advanceUntilIdle()

        assertTrue(renderer.visible.isEmpty())
        coordinator.stop()
    }

    @Test
    fun `window change clears everything`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        coordinator.submit(TextSourceEvent.Cleared)
        advanceUntilIdle()

        assertTrue(renderer.visible.isEmpty())
        assertTrue(renderer.updates.any { it is RenderUpdate.ClearAll })
        coordinator.stop()
    }

    @Test
    fun `sensitive input never reaches the provider`() = runTest {
        val coordinator = start()

        coordinator.submit(
            TextSourceEvent.Upserted(
                listOf(TestElements.element(text = "hunter2", isProtected = true)),
            ),
        )
        advanceUntilIdle()

        assertEquals(0, translator.callCount)
        assertEquals(0, cache.writes.size)
        assertTrue(renderer.visible.isEmpty())
        assertTrue(logger.neverLogged("hunter2"))
        coordinator.stop()
    }

    @Test
    fun `cached translations skip the provider`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()
        assertEquals(1, translator.callCount)

        // Same text arriving as a different element still hits the cache.
        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(id = "e2", text = "Hello"))),
        )
        advanceUntilIdle()

        assertEquals(1, translator.callCount)
        assertEquals(1, cache.hits)
        coordinator.stop()
    }

    @Test
    fun `retryable failures are retried with backoff`() = runTest {
        translator.failWith = TranslationError.Network("timeout")
        translator.failTimes = 2
        val coordinator = start(CoordinatorConfig(maxRetries = 2, retryBaseDelayMillis = 100))

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(3, translator.callCount)
        assertEquals(listOf("<Hello>"), renderer.visibleText)
        coordinator.stop()
    }

    @Test
    fun `non-retryable failures are not retried`() = runTest {
        translator.failWith = TranslationError.ProviderRejected(ProviderId("fake"), "bad key")
        val coordinator = start(CoordinatorConfig(maxRetries = 2, retryBaseDelayMillis = 100))

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(1, translator.callCount)
        coordinator.stop()
    }

    @Test
    fun `a failing element does not stop the pipeline`() = runTest {
        translator.failWith = TranslationError.ProviderRejected(ProviderId("fake"))
        val coordinator = start()

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()
        assertEquals(TranslationRuntimeState.Running, coordinator.runtimeState.value)

        translator.failWith = null
        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(id = "e2", text = "World"))),
        )
        advanceUntilIdle()

        assertEquals(listOf("<World>"), renderer.visibleText)
        coordinator.stop()
    }

    @Test
    fun `changing target language produces fresh translations`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()
        assertEquals(1, translator.callCount)

        settings.setTargetLanguageMode(TargetLanguageMode.Manual(LanguageTag("ja")))
        advanceUntilIdle()

        assertEquals(2, translator.callCount)
        assertEquals(LanguageTag("ja"), translator.requests.last().languages.target)
        coordinator.stop()
    }

    @Test
    fun `manual source language reaches the provider`() = runTest {
        settings.setSourceLanguageMode(SourceLanguageMode.Manual(LanguageTag("en")))
        val coordinator = start()

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(LanguageTag("en"), translator.requests.last().languages.source)
        coordinator.stop()
    }

    @Test
    fun `stop clears overlays and disables the runtime`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        coordinator.stop()
        advanceUntilIdle()

        assertEquals(TranslationRuntimeState.Disabled, coordinator.runtimeState.value)
        assertTrue(renderer.visible.isEmpty())
    }

    @Test
    fun `submissions after stop are ignored`() = runTest {
        val coordinator = start()
        coordinator.stop()
        advanceUntilIdle()

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(0, translator.callCount)
    }

    @Test
    fun `pause stops translating new content but keeps what is shown`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        coordinator.pause()
        advanceUntilIdle()
        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(id = "e2", text = "World"))),
        )
        advanceUntilIdle()

        assertEquals(TranslationRuntimeState.Paused, coordinator.runtimeState.value)
        assertEquals(1, translator.callCount)
        assertEquals(listOf("<Hello>"), renderer.visibleText)
        coordinator.stop()
    }

    @Test
    fun `resuming after pause returns to running`() = runTest {
        val coordinator = start()
        coordinator.pause()
        coordinator.start()
        advanceUntilIdle()

        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        assertEquals(TranslationRuntimeState.Running, coordinator.runtimeState.value)
        assertEquals(listOf("<Hello>"), renderer.visibleText)
        coordinator.stop()
    }

    @Test
    fun `removal still applies while paused`() = runTest {
        val coordinator = start()
        coordinator.submit(TextSourceEvent.Upserted(listOf(TestElements.element(text = "Hello"))))
        advanceUntilIdle()

        coordinator.pause()
        coordinator.submit(TextSourceEvent.Removed(listOf(TextElementId("e1"))))
        advanceUntilIdle()

        assertTrue(renderer.visible.isEmpty())
        coordinator.stop()
    }

    @Test
    fun `concurrent translations stay within the configured limit`() = runTest {
        translator.delayMillis = 100
        val coordinator = start(CoordinatorConfig(maxConcurrentTranslations = 3))

        val elements = (1..12).map { TestElements.element(id = "e$it", text = "text $it") }
        coordinator.submit(TextSourceEvent.Upserted(elements))
        advanceUntilIdle()

        assertTrue(
            translator.peakConcurrency <= 3,
            "peak concurrency was ${translator.peakConcurrency}",
        )
        assertEquals(12, renderer.visible.size)
        coordinator.stop()
    }

    @Test
    fun `screen text never appears in diagnostics`() = runTest {
        translator.failWith = TranslationError.ProviderRejected(ProviderId("fake"))
        val coordinator = start()

        coordinator.submit(
            TextSourceEvent.Upserted(listOf(TestElements.element(text = "confidential memo"))),
        )
        advanceUntilIdle()

        assertFalse(logger.entries.isEmpty(), "expected a diagnostic for the failure")
        assertTrue(logger.neverLogged("confidential memo"))
        coordinator.stop()
    }
}
