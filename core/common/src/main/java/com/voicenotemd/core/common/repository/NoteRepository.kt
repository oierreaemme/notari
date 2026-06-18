package com.voicenotemd.core.common.repository

import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.domain.TagUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /**
     * Stream the tag corpus as (tag, note-language) pairs — the projection the
     * structuring flow feeds to the EXISTING_TAGS prompt list, scoped per language
     * (ADR 0012 / ADR 0017). Distinct pairs, no ordering guarantee.
     *
     * Default derives from [observeAll] so lightweight fakes keep compiling; the Room
     * implementation overrides it with a dedicated join query so the capture flow does
     * not have to hold every note body in memory (review 2026-06-10 #13).
     */
    fun observeTagCorpus(): Flow<List<TagUsage>> =
        observeAll().map { notes ->
            notes
                .flatMap { note -> note.tags.map { TagUsage(it, note.language) } }
                .distinct()
        }

    suspend fun insert(note: Note)

    suspend fun update(note: Note)

    suspend fun delete(id: String)

    /**
     * Hard-delete every note. Used by the privacy "delete everything" button in
     * Settings. Irreversible.
     */
    suspend fun deleteAll()
}
