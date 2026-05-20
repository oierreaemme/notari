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
    }
}
