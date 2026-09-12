package com.babel.data.settings.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.babel.data.settings.AndroidSystemLocaleProvider
import com.babel.data.settings.DataStoreSettingsRepository
import com.babel.domain.language.SystemLocaleProvider
import com.babel.domain.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Distinguishes the settings store from any other DataStore added later. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object SettingsProvidesModule {

    private const val STORE_NAME = "babel_settings"

    @Provides
    @Singleton
    @SettingsDataStore
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // Survives the whole process: settings outlive any single service.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
    )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @SettingsDataStore dataStore: DataStore<Preferences>,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindsModule {

    @Binds
    abstract fun bindSystemLocaleProvider(
        impl: AndroidSystemLocaleProvider,
    ): SystemLocaleProvider
}
