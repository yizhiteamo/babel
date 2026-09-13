package com.babel.platform.accessibility.di

import com.babel.domain.vision.ScreenCaptureController
import com.babel.platform.accessibility.AccessibilityScreenshotSource
import com.babel.platform.accessibility.MangaModeController
import com.babel.platform.screen.ScreenFrameSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
}
