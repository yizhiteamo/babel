package com.babel.platform.accessibility.di

import com.babel.core.common.BabelLogger
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.ScreenCaptureController
import com.babel.platform.accessibility.AccessibilityScreenshotSource
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
 * Publishes the two things the rest of the app needs from this module without
 * seeing it: who takes the pictures, and whether manga mode is on.
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
    }
}
