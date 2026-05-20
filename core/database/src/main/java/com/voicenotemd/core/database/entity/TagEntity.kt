package com.voicenotemd.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A tag attached to a note. Modeled as a join table so we can index lookups by tag value
 * for O(log n) tag-filtered queries even on a large note collection.
 *
 * Each (noteId, value) pair is unique — multiple identical tags on the same note collapse
 * into one. Cascading delete: when a note is deleted, its tags go with it.
 */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["note_id", "value"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("value")],
)
data class TagEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "value") val value: String,
)
