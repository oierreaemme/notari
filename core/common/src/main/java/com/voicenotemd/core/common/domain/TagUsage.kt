package com.voicenotemd.core.common.domain

/**
 * One tag as used somewhere in the corpus, with the language of the note it appears on.
 *
 * This is the minimal projection the structuring flow needs to build the EXISTING_TAGS
 * prompt list scoped to the dictation language (ADR 0012 / ADR 0017) — it replaces
 * keeping every full [Note] in memory just to read its tags (review 2026-06-10 #13).
 */
data class TagUsage(
    val tag: Tag,
    val language: Language,
)
