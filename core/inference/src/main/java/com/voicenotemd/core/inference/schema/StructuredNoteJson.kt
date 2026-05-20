package com.voicenotemd.core.inference.schema

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The exact JSON schema that the model is contracted to emit. See
 * `assets/prompts/structure_note_v1.txt` for the source of truth — this file mirrors that
 * contract in code.
 *
 * We tolerate `null` values per CLAUDE.md fault-tolerance rules. If a field is missing
 * the parser fails fast and we fall back to plain-text storage (pillar 3).
 */
@JsonClass(generateAdapter = true)
data class StructuredNoteJson(
    @Json(name = "language") val language: String?,
    @Json(name = "title") val title: String?,
    @Json(name = "tags") val tags: List<String>?,
    @Json(name = "mentions") val mentions: List<MentionJson>?,
    @Json(name = "body_markdown") val bodyMarkdown: String?,
)

@JsonClass(generateAdapter = true)
data class MentionJson(
    @Json(name = "surface_form") val surfaceForm: String?,
    @Json(name = "iso_resolved") val isoResolved: String?,
)
