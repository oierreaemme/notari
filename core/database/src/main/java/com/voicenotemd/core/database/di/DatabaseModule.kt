package com.voicenotemd.core.database.di

import android.content.Context
import androidx.room.Room
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.database.VoiceNoteDatabase
import com.voicenotemd.core.database.dao.NoteDao
import com.voicenotemd.core.database.repository.NoteRepositoryImpl
import com.voicenotemd.core.database.security.DatabasePassphraseProvider
import com.voicenotemd.core.database.security.PlaintextToEncryptedMigrator
import com.voicenotemd.core.database.security.toSqlCipherPassphrase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): VoiceNoteDatabase {
        // Load the SQLCipher native library unconditionally — required before ANY
        // SQLiteDatabase or SupportOpenHelperFactory call, including Room's internal open.
        // SupportOpenHelperFactory does NOT auto-load the native lib on net.zetetic:sqlcipher-android.
        System.loadLibrary("sqlcipher")

        val passphrase = DatabasePassphraseProvider(context).getPassphrase()

        // One-time migration: plaintext → encrypted. The migrator self-detects by reading
        // the DB file's magic header (`SQLite format 3\0` = plaintext, anything else =
        // already encrypted or no DB yet); no sidecar sentinel needed. See ADR 0019.
        PlaintextToEncryptedMigrator(context).migrateIfNeeded(passphrase)

        // We pass the SQLCipher *passphrase* form (base64 of the random 32 bytes) rather than
        // the raw bytes themselves — see [toSqlCipherPassphrase] — so every SQLCipher call
        // site (this factory, the migrator's openOrCreateDatabase, the ATTACH KEY in
        // sqlcipher_export, and the verify open) PBKDF2-derives the same underlying page key.
        //
        // IMPORTANT: SupportOpenHelperFactory keeps the byte[] BY REFERENCE and reads it
        // lazily on every (re)open — it does NOT copy. Zeroing this array here would make
        // Room open the database with an all-zero key (silently corrupting fresh installs
        // and crashing migrated ones with "file is not a database"). So we must NOT wipe
        // passphraseBytes; it has to outlive this method for the lifetime of the DB. We can
        // still zero the raw 32-byte secret, which the factory does not hold onto.
        val passphraseBytes = passphrase.toSqlCipherPassphrase().toByteArray(Charsets.US_ASCII)
        val factory = SupportOpenHelperFactory(passphraseBytes)
        passphrase.fill(0)

        return Room.databaseBuilder(
            context,
            VoiceNoteDatabase::class.java,
            VoiceNoteDatabase.DATABASE_NAME,
        )
            .openHelperFactory(factory)
            // Notes are user-generated content. We do not allow Room to silently fall back
            // to destructive migrations — every schema bump must ship an explicit migration.
            .build()
    }

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
