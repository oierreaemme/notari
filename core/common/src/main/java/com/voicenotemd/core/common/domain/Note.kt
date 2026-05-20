package com.voicenotemd.core.common.domain

import java.time.Instant

/**
 * The final, persisted form of a captured note.
 *
 * If structuring failed and the user kept the plain transcript (per CLAUDE.md pillar 3),
 * [structured] is `false`, [title] is the truncated first line of the transcript, and
 * [bodyMarkdown] is the raw transcript. The note is still saved — the user is never
 * blocked by a model failure.
 */
data class Note(
    val id: String,
    val title: String,
    val bodyMarkdown: String,
    val tags: List<Tag>,
    val mentions: List<DateMention>,
    val language: Language,
    val createdAt: Instant,
    val updatedAt: Instant,
    val structured: Boolean,
)

/**
 * A datetime mention extracted from the transcript. We keep the original surface form
 * ("domani alle 15", "next Friday") AND a best-effort resolution. The resolution can
 * be null when the model was unsure — we never invent.
 */
data class DateMention(
    val surfaceForm: String,
    val resolved: Instant?,
)

/**
 * The raw structured payload Gemma emits, BEFORE we map it to a [Note]. Kept as a
 * separate type so the inference layer doesn't import [Note] (clean separation, ADR 0001).
 */
data class StructuredNote(
    val title: String,
    val bodyMarkdown: String,
    val tags: List<String>,
    val mentions: List<RawDateMention>,
    val languageBcp47: String,
)

data class RawDateMention(
    val surfaceForm: String,
    // ISO-8601 string OR null. Validated downstream.
    val isoResolved: String?,
)
