package com.voicenotemd.core.database.mapper

import com.voicenotemd.core.common.domain.DateMention
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.database.dao.NoteWithRelations
import com.voicenotemd.core.database.entity.MentionEntity
import com.voicenotemd.core.database.entity.NoteEntity
import com.voicenotemd.core.database.entity.TagEntity
import java.time.Instant

internal fun NoteWithRelations.toDomain(): Note =
    Note(
        id = note.id,
        title = note.title,
        bodyMarkdown = note.bodyMarkdown,
        tags = tags.mapNotNull { Tag.normalize(it.value) }.distinct(),
        mentions = mentions.map(MentionEntity::toDomain),
        language = Language.fromBcp47(note.language),
        createdAt = Instant.ofEpochMilli(note.createdAt),
        updatedAt = Instant.ofEpochMilli(note.updatedAt),
        structured = note.structured,
    )

internal fun MentionEntity.toDomain(): DateMention =
    DateMention(
        surfaceForm = surfaceForm,
        resolved = resolvedAt?.let(Instant::ofEpochMilli),
    )

internal fun Note.toEntity(): NoteEntity =
    NoteEntity(
        id = id,
        title = title,
        bodyMarkdown = bodyMarkdown,
        language = language.bcp47,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        structured = structured,
    )

internal fun Note.toTagEntities(): List<TagEntity> = tags.map { TagEntity(noteId = id, value = it.value) }

internal fun Note.toMentionEntities(): List<MentionEntity> =
    mentions.map {
        MentionEntity(
            noteId = id,
            surfaceForm = it.surfaceForm,
            resolvedAt = it.resolved?.toEpochMilli(),
        )
    }
