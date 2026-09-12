package com.babel.platform.overlay.di

import com.babel.domain.render.TranslationRenderer
import com.babel.platform.overlay.OverlayRenderer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OverlayModule {

    @Binds
    @Singleton
    abstract fun bindTranslationRenderer(impl: OverlayRenderer): TranslationRenderer
}
