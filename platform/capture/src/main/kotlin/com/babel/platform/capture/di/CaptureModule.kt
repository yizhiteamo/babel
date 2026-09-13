package com.babel.platform.capture.di

import com.babel.domain.vision.ImageTextScanner
import com.babel.platform.capture.CaptureTextSource
import com.babel.platform.capture.MlKitTextRecognizer
import com.babel.platform.capture.TextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Publishes scanning as the domain contract, so the accessibility service can
 * drive it without depending on this module — which would pull ML Kit into the
 * V1 path (ADR 009).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindImageTextScanner(impl: CaptureTextSource): ImageTextScanner
}

/**
 * Kept separate and internal because both sides of this binding are internal:
 * nothing outside needs to know which engine reads the pixels, which is the
 * point of the abstraction.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecognizerModule {

    @Binds
    @Singleton
    abstract fun bindTextRecognizer(impl: MlKitTextRecognizer): TextRecognizer
}
