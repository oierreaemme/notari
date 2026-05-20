package com.voicenotemd.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [Index(value = ["created_at"])],
)
data class NoteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "body_markdown") val bodyMarkdown: String,
    @ColumnInfo(name = "language") val language: String,
    // epoch millis
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "structured") val structured: Boolean,
)
