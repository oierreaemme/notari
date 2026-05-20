package com.voicenotemd.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.datastore.PreferencesSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private const val SETTINGS_DATASTORE_NAME = "voice_note_settings"

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: PreferencesSettingsRepository): SettingsRepository
}
