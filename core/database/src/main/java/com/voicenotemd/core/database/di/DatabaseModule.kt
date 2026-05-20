package com.voicenotemd.core.database.di

import android.content.Context
import androidx.room.Room
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.database.VoiceNoteDatabase
import com.voicenotemd.core.database.dao.NoteDao
import com.voicenotemd.core.database.repository.NoteRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): VoiceNoteDatabase =
        Room.databaseBuilder(
            context,
            VoiceNoteDatabase::class.java,
            VoiceNoteDatabase.DATABASE_NAME,
        )
            // Notes are user-generated content. We do not allow Room to silently fall back
            // to destructive migrations — every schema bump must ship an explicit migration.
            .build()

    @Provides
    fun provideNoteDao(database: VoiceNoteDatabase): NoteDao = database.noteDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindingsModule {
    @Binds
    @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository
}
