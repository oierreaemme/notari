package com.voicenotemd.core.common.repository

import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import kotlinx.coroutines.flow.Flow

/**
 * The single point of access to the persisted note collection.
 *
 * Implemented in :core:database — but the contract lives here so that domain
 * code and feature code can both depend on it without pulling in Room.
 */
interface NoteRepository {
    /**
     * Stream all notes, newest first. Emits a fresh list whenever the underlying
     * data changes (Room's `Flow` semantics).
     */
    fun observeAll(): Flow<List<Note>>

    /**
     * Stream a single note. Emits `null` when the note is deleted.
     */
    fun observe(id: String): Flow<Note?>

    /**
     * Stream all notes that carry the given tag, newest first.
     */
    fun observeByTag(tag: Tag): Flow<List<Note>>

    /**
     * Stream the union of tags currently in use, sorted alphabetically.
     */
    fun observeAllTags(): Flow<List<Tag>>

    suspend fun insert(note: Note)

    suspend fun update(note: Note)

    suspend fun delete(id: String)

    /**
     * Hard-delete every note. Used by the privacy "delete everything" button in
     * Settings. Irreversible.
     */
    suspend fun deleteAll()
}
