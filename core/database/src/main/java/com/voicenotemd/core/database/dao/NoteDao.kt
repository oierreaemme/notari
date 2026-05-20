package com.voicenotemd.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.voicenotemd.core.database.entity.MentionEntity
import com.voicenotemd.core.database.entity.NoteEntity
import com.voicenotemd.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Transaction
    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<NoteWithRelations>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<NoteWithRelations?>

    @Transaction
    @Query(
        """
        SELECT n.* FROM notes n
        INNER JOIN note_tags t ON t.note_id = n.id
        WHERE t.value = :tagValue
        ORDER BY n.created_at DESC
        """,
    )
    fun observeByTag(tagValue: String): Flow<List<NoteWithRelations>>

    @Query("SELECT DISTINCT value FROM note_tags ORDER BY value ASC")
    fun observeAllTagValues(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMentions(mentions: List<MentionEntity>)

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    suspend fun deleteTagsFor(noteId: String)

    @Query("DELETE FROM note_mentions WHERE note_id = :noteId")
    suspend fun deleteMentionsFor(noteId: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()

    /**
     * Single-transaction insert that writes the note plus its tags and mentions atomically.
     * Used by the repository so we never end up with half-written notes.
     */
    @Transaction
    suspend fun upsertNoteWithRelations(
        note: NoteEntity,
        tags: List<TagEntity>,
        mentions: List<MentionEntity>,
    ) {
        insertNote(note)
        deleteTagsFor(note.id)
        deleteMentionsFor(note.id)
        if (tags.isNotEmpty()) insertTags(tags)
        if (mentions.isNotEmpty()) insertMentions(mentions)
    }
}

data class NoteWithRelations(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val mentions: List<MentionEntity>,
)
