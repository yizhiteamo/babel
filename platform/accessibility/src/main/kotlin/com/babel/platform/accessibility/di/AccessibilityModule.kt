package com.babel.platform.accessibility.di

import com.babel.core.common.BabelLogger
import com.babel.domain.game.GameCaptureController
import com.babel.domain.runtime.AccessibilityRecovery
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.ScreenCaptureController
import com.babel.platform.accessibility.AccessibilityScreenshotSource
import com.babel.platform.accessibility.AccessibilityServiceRestorer
import com.babel.platform.accessibility.GameModeController
import com.babel.platform.accessibility.MangaModeController
import com.babel.platform.screen.ScreenFrameSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Publishes what the rest of the app needs from this module without seeing it:
 * who takes the pictures, whether manga mode is on, and how the service is put
 * back after a force stop.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityModule {

    @Binds
    @Singleton
    abstract fun bindScreenFrameSource(impl: AccessibilityScreenshotSource): ScreenFrameSource

    @Binds
    @Singleton
    abstract fun bindCaptureController(impl: MangaModeController): ScreenCaptureController

    @Binds
    @Singleton
    abstract fun bindTrustedWindows(impl: AccessibilityScreenshotSource): com.babel.platform.screen.TrustedWindows

    @Binds
    @Singleton
    abstract fun bindTapForwarder(impl: AccessibilityScreenshotSource): com.babel.platform.screen.TapForwarder

    @Binds
    @Singleton
    abstract fun bindGameController(impl: GameModeController): GameCaptureController

    @Binds
    @Singleton
    abstract fun bindRecovery(impl: AccessibilityServiceRestorer): AccessibilityRecovery

    companion object {

        /**
         * Built here rather than injected because the controller needs a scope
         * of its own to read and write one setting, and the module is where the
         * other layers put theirs (`SettingsModule`, `TranslationModule`).
         */
        @Provides
        @Singleton
        fun provideMangaModeController(
            frames: ScreenFrameSource,
            settings: SettingsRepository,
            logger: BabelLogger,
        ): MangaModeController = MangaModeController(
            frames = frames,
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            logger = logger,
        )

        @Provides
        @Singleton
        fun provideGameModeController(
            frames: ScreenFrameSource,
            settings: SettingsRepository,
            logger: BabelLogger,
        ): GameModeController = GameModeController(
            frames = frames,
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            logger = logger,
        )
    }
}
