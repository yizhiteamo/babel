package com.babel.app.di

import com.babel.app.capability.AndroidCapabilityChecker
import com.babel.app.logging.AndroidLogger
import com.babel.core.common.BabelLogger
import com.babel.core.common.DefaultDispatcherProvider
import com.babel.core.common.DispatcherProvider
import com.babel.domain.language.LanguageResolver
import com.babel.domain.privacy.DefaultSensitiveContentPolicy
import com.babel.domain.privacy.SensitiveContentPolicy
import com.babel.domain.runtime.CapabilityChecker
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.translation.DefaultTranslationCoordinator
import com.babel.domain.translation.TranslationCache
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.translation.Translator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Assembles the pure-Kotlin domain, which cannot carry Hilt annotations itself.
 *
 * This is the wiring `:app` exists for. Behaviour does not belong here — only
 * the choice of which implementation satisfies which contract.
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideSensitiveContentPolicy(): SensitiveContentPolicy =
        DefaultSensitiveContentPolicy()

    @Provides
    @Singleton
    fun provideTranslationCoordinator(
        translator: Translator,
        cache: TranslationCache,
        languageResolver: LanguageResolver,
        settingsRepository: SettingsRepository,
        sensitivePolicy: SensitiveContentPolicy,
        dispatchers: DispatcherProvider,
        logger: BabelLogger,
    ): TranslationCoordinator = DefaultTranslationCoordinator(
        translator = translator,
        cache = cache,
        languageResolver = languageResolver,
        settingsRepository = settingsRepository,
        sensitivePolicy = sensitivePolicy,
        dispatchers = dispatchers,
        logger = logger,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {

    @Binds
    abstract fun bindLogger(impl: AndroidLogger): BabelLogger

    @Binds
    abstract fun bindCapabilityChecker(impl: AndroidCapabilityChecker): CapabilityChecker
}
