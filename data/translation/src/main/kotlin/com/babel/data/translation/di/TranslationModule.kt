package com.babel.data.translation.di

import com.babel.data.translation.InMemoryTranslationCache
import com.babel.data.translation.mlkit.MlKitLanguages
import com.babel.data.translation.mlkit.MlKitTranslator
import com.babel.domain.language.DefaultLanguageResolver
import com.babel.domain.language.LanguageResolver
import com.babel.domain.language.SystemLocaleProvider
import com.babel.domain.translation.FragmentingTranslator
import com.babel.domain.translation.TranslationCache
import com.babel.core.common.BabelLogger
import com.babel.domain.translation.Translator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TranslationModule {

    /**
     * The provider, wrapped so a line of comic dialogue is translated one
     * ellipsis-separated fragment at a time.
     *
     * The wrapper is provider-neutral and reports the provider's own id, so
     * cache keys are unaffected and swapping the engine keeps the behaviour
     * (`docs/milestones/v2.md`).
     */
    @Provides
    @Singleton
    fun provideTranslator(logger: BabelLogger): Translator =
        FragmentingTranslator(MlKitTranslator(logger = logger))

    @Provides
    @Singleton
    fun provideTranslationCache(): TranslationCache = InMemoryTranslationCache()

    /**
     * Bound here rather than in `:app` because the supported-language list is a
     * provider capability, and this module is the one that knows it. Swapping
     * the provider swaps the language list with it.
     */
    @Provides
    @Singleton
    fun provideLanguageResolver(
        systemLocaleProvider: SystemLocaleProvider,
    ): LanguageResolver = DefaultLanguageResolver(
        systemLocaleProvider = systemLocaleProvider,
        supported = MlKitLanguages.supportedTags(),
    )
}
