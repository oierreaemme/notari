package com.voicenotemd.core.database.repository

import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.database.dao.NoteDao
import com.voicenotemd.core.database.mapper.toDomain
import com.voicenotemd.core.database.mapper.toEntity
import com.voicenotemd.core.database.mapper.toMentionEntities
import com.voicenotemd.core.database.mapper.toTagEntities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NoteRepositoryImpl
    @Inject
    constructor(
        private val dao: NoteDao,
    ) : NoteRepository {
        override fun observeAll(): Flow<List<Note>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        override fun observe(id: String): Flow<Note?> = dao.observe(id).map { it?.toDomain() }

        override fun observeByTag(tag: Tag): Flow<List<Note>> =
            dao.observeByTag(tag.value).map { rows -> rows.map { it.toDomain() } }

        override fun observeAllTags(): Flow<List<Tag>> =
            dao.observeAllTagValues().map { values -> values.mapNotNull(Tag.Companion::normalize) }

        override suspend fun insert(note: Note) {
            dao.upsertNoteWithRelations(
                note = note.toEntity(),
                tags = note.toTagEntities(),
                mentions = note.toMentionEntities(),
            )
        }

        override suspend fun update(note: Note) = insert(note)

        override suspend fun delete(id: String) = dao.deleteNote(id)

        override suspend fun deleteAll() = dao.deleteAllNotes()
    }
