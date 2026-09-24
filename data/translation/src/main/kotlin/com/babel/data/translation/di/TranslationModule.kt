package com.babel.data.translation.di

import com.babel.data.translation.InMemoryTranslationCache
import com.babel.data.translation.mlkit.MlKitLanguages
import com.babel.data.translation.mlkit.MlKitTranslator
import com.babel.domain.language.DefaultLanguageResolver
import com.babel.domain.language.LanguageResolver
import com.babel.domain.language.SystemLocaleProvider
import com.babel.core.common.DispatcherProvider
import com.babel.data.translation.remote.ChatTranslator
import com.babel.data.translation.remote.DeepLTranslator
import com.babel.data.translation.remote.RemoteTranslator
import com.babel.domain.settings.SettingsRepository
import com.babel.data.translation.remote.TranslatingRemoteProbe
import com.babel.domain.translation.FragmentingTranslator
import com.babel.domain.translation.RemoteProbe
import com.babel.domain.translation.RoutingTranslator
import com.babel.domain.translation.TranslationCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
     * Whichever translator the user has asked for, on-device by default.
     *
     * The two routes are wrapped differently, and that is the point.
     * [FragmentingTranslator] cuts a line at its ellipses, which measurably
     * recovers sentences ML Kit would otherwise drop — but it is a compensation
     * for a weak engine. A stronger one does better on whole balloons and worse
     * on fragments (`docs/milestones/v2.md`), so the remote route is left
     * unwrapped.
     *
     * The remote arm is itself a choice of service — a chat endpoint or DeepL —
     * made by [RemoteTranslator]. `RoutingTranslator` stays a two-way question
     * because that is the one the privacy gate asks.
     *
     * The scope outlives any screen: the route follows settings for as long as
     * the process runs, and the coordinator holds this translator for the same
     * span.
     */
    @Provides
    @Singleton
    fun provideTranslator(
        settingsRepository: SettingsRepository,
        dispatchers: DispatcherProvider,
        logger: BabelLogger,
    ): Translator {
        // One scope for both followers of settings: they live exactly as long
        // as each other and as long as the translator they make up.
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        return RoutingTranslator(
            local = FragmentingTranslator(MlKitTranslator(logger = logger)),
            remote = RemoteTranslator(
                chat = ChatTranslator(settingsRepository, logger),
                deepL = DeepLTranslator(settingsRepository, logger),
                settingsRepository = settingsRepository,
                scope = scope,
            ),
            settingsRepository = settingsRepository,
            scope = scope,
        )
    }

    /**
     * The settings check the online-translation dialog offers.
     *
     * Provided rather than bound because it takes the logger, and because the
     * UI must reach it as a domain contract: `:app` should be able to ask
     * whether a configuration works without knowing that DeepL and a chat
     * endpoint are two different classes.
     */
    @Provides
    @Singleton
    fun provideRemoteProbe(logger: BabelLogger): RemoteProbe = TranslatingRemoteProbe(logger)

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
