package com.voicenotemd.core.inference.prompt

import com.voicenotemd.core.common.domain.Language
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Loader for the versioned structuring prompts that live under `assets/prompts/`.
 *
 * The active prompt is referenced by name only — never inlined as a string literal —
 * so changing the prompt is always a versioned, file-based change with a corresponding
 * entry in `docs/prompt-evaluations/`.
 */
interface PromptTemplate {
    /**
     * Render the prompt with [transcript] inlined.
     *
     * The template supports these markers:
     * - `{{TRANSCRIPT}}` — substituted with [transcript]
     * - `{{NOW_ISO}}` — substituted with [now] formatted as ISO-8601 with timezone offset
     *   in [zone], e.g. `2026-05-13T16:42:00+02:00`. Lets Gemma resolve relative
     *   datetimes ("tomorrow at 3pm", "domani alle 15") to absolute ISO values
     *   instead of leaving them as `null`.
     * - `{{NOW_TIMEZONE}}` — substituted with the IANA zone id (e.g. `Europe/Rome`).
     * - `{{EXISTING_TAGS}}` — substituted with a comma-separated list of the tags
     *   already in use across the user's notes. The prompt body uses this list to
     *   nudge Gemma toward reusing a familiar tag instead of coining a synonymous
     *   new one ("app" vs "app-development" for the same topic). Empty when there
     *   are no prior tags.
     *
     * @param zone the timezone to anchor `{{NOW_ISO}}` and `{{NOW_TIMEZONE}}` to.
     *   Defaults to the system default so device-local times feel natural in the
     *   resolved datetimes. Tests pass [ZoneOffset.UTC] for determinism.
     */
    fun render(
        transcript: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        existingTags: List<String> = emptyList(),
    ): String
}

class StaticPromptTemplate(private val template: String) : PromptTemplate {
    override fun render(
        transcript: String,
        now: Instant,
        zone: ZoneId,
        existingTags: List<String>,
    ): String {
        val nowFormatted = ISO_OFFSET.format(now.atZone(zone))
        // Cap to a sane upper bound so a user with hundreds of tags doesn't blow
        // the prompt token budget. The cap is generous; corpora with this many
        // tags will already have well-established naming conventions for the
        // model to mimic.
        val tagsForPrompt =
            existingTags
                .take(MAX_EXISTING_TAGS_IN_PROMPT)
                .joinToString(", ")
        return template
            .replace(TRANSCRIPT_MARKER, transcript)
            .replace(NOW_ISO_MARKER, nowFormatted)
            .replace(NOW_TIMEZONE_MARKER, zone.id)
            .replace(EXISTING_TAGS_MARKER, tagsForPrompt)
    }

    companion object {
        const val TRANSCRIPT_MARKER = "{{TRANSCRIPT}}"
        const val NOW_ISO_MARKER = "{{NOW_ISO}}"
        const val NOW_TIMEZONE_MARKER = "{{NOW_TIMEZONE}}"
        const val EXISTING_TAGS_MARKER = "{{EXISTING_TAGS}}"

        const val MAX_EXISTING_TAGS_IN_PROMPT = 50

        /**
         * `yyyy-MM-dd'T'HH:mm:ss±HH:mm` — fits the schema Gemma sees in the prompt
         * examples and matches `Instant.parse` round-trip when we later validate
         * `iso_resolved` downstream.
         */
        private val ISO_OFFSET: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}

/**
 * The "stricter" prompt used on the second pass. We don't inline this in code with a
 * giant string — we keep it short and surgical, because the failure mode it targets
 * is "the model added prose around its JSON". A short, blunt instruction is exactly
 * what works for that. The wrapped [basePrompt] still receives the temporal context
 * markers so the second-attempt JSON also resolves dates correctly.
 */
class StricterPromptTemplate(private val basePrompt: PromptTemplate) : PromptTemplate {
    override fun render(
        transcript: String,
        now: Instant,
        zone: ZoneId,
        existingTags: List<String>,
    ): String =
        "RETURN JSON ONLY. NO OTHER TEXT. The previous response could not be parsed. " +
            "Output the structured note for the following transcript.\n\n" +
            basePrompt.render(transcript, now, zone, existingTags)
}

/**
 * Wraps [basePrompt] with an explicit single-language directive, used ONLY when the
 * user has pinned a dictation language.
 *
 * Why this exists: previously `forceLanguage` set the stored [Language] enum and the
 * ASR recognizer locale, but it never reached the structuring prompt — so Gemma always
 * auto-detected, and on short notes E2B could slip (real device, 2026-05-22: an English
 * dictation produced a mixed IT/EN title and Italian tags). When the user has pinned a
 * language, prepending a blunt "write EVERYTHING in <lang>" instruction makes the pin
 * actually constrain the output. With no pin this decorator is simply not used, so the
 * base prompt's own "detect the language" rule still governs the auto case unchanged.
 */
class LanguageScopedPromptTemplate(
    private val basePrompt: PromptTemplate,
    private val language: Language,
) : PromptTemplate {
    override fun render(
        transcript: String,
        now: Instant,
        zone: ZoneId,
        existingTags: List<String>,
    ): String =
        "LANGUAGE LOCK — the user selected \"${language.bcp47}\" as the dictation " +
            "language. Write the \"title\", every entry in \"tags\", \"body_markdown\", " +
            "and each date \"surface_form\" ALL in \"${language.bcp47}\". Do NOT mix " +
            "languages and do NOT borrow the language of the examples below. The " +
            "\"language\" field MUST be \"${language.bcp47}\".\n\n" +
            basePrompt.render(transcript, now, zone, existingTags)
}
