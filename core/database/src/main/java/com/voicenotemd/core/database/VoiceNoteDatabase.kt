package com.voicenotemd.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.voicenotemd.core.database.dao.NoteDao
import com.voicenotemd.core.database.entity.MentionEntity
import com.voicenotemd.core.database.entity.NoteEntity
import com.voicenotemd.core.database.entity.TagEntity

@Database(
    entities = [NoteEntity::class, TagEntity::class, MentionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VoiceNoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        const val DATABASE_NAME = "voice_note.db"

        // Keep in sync with the @Database(version = …) above. Used by the SQLCipher
        // migrator when it opens the encrypted DB through the same SupportSQLiteOpenHelper
        // path Room uses, so the verify step matches production exactly (ADR 0019).
        const val SCHEMA_VERSION = 1
    }
}
