package com.babel.platform.capture.di

import com.babel.platform.capture.MlKitTextRecognizer
import com.babel.platform.capture.TextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the recogniser inside this module, because the contract and its
 * implementation are both `internal` — nothing outside needs to know which
 * engine reads the pixels, which is the point of the abstraction.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindTextRecognizer(impl: MlKitTextRecognizer): TextRecognizer
}
