package com.babel.platform.game.di

import com.babel.domain.game.GameTextScanner
import com.babel.platform.game.GameTextSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Publishes game scanning as the domain contract, so the accessibility service
 * drives it without depending on this module (and on ML Kit).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GameModule {

    @Binds
    @Singleton
    abstract fun bindGameTextScanner(impl: GameTextSource): GameTextScanner
}
