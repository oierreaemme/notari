package com.voicenotemd.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_mentions",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("note_id")],
)
data class MentionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "surface_form") val surfaceForm: String,
    // epoch millis or null
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long?,
)
