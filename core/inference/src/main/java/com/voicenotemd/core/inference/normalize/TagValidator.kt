package com.voicenotemd.core.inference.normalize

import com.voicenotemd.core.common.domain.Tag
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic guard against tag hallucination.
 *
 * Gemma sometimes emits tags that are semantically untethered to the transcript
 * — we observed `["app-development", "lavoro", "rag"]` on a note about deploys
 * and sync meetings, where "rag" came from a prior corpus entry but had no
 * connection to the current note's content.
 *
 * The deterministic rule (current policy, 2026-05-16 — after the earlier
 * corpus-anchoring rule produced 75% empty tag arrays in the field):
 *
 *  - **Multi-part kebab tag** (`app-development`, `ai-research`): KEPT only if
 *    any part ≥3 chars appears as a standalone word in the transcript. The
 *    word-boundary check stops "app" from matching inside "appuntamento".
 *  - **Mono-part tag ≥4 chars** (`lavoro`, `personale`, `sogni`): TRUSTED and
 *    kept unconditionally — these are semantic abstractions that legitimately
 *    have no literal anchor in the transcript. The ~1-in-20 hallucinated long
 *    tag that slips through is the accepted price of not shipping mostly-empty
 *    tag arrays (the single worst tag-discoverability outcome).
 *  - **Mono-part tag ≤3 chars** (`rag`, `seo`, `ai`): KEPT only if it appears
 *    as a standalone word. Short tags are both likelier to be context-bleed
 *    hallucinations and likelier to false-match if allowed loose.
 *
 * Everything else is silently stripped — the user never sees a tag the model
 * fabricated from context that didn't exist.
 *
 * **Why no corpus argument.** The user's prior tag vocabulary (EXISTING_TAGS)
 * steers tag *generation* upstream, in the prompt — see `StaticPromptTemplate`'s
 * `{{EXISTING_TAGS}}` marker (ADR 0012). This validator runs *downstream* as a
 * pure hallucination backstop, so it deliberately does not re-consult the
 * corpus: doing so was the lexical-anchoring rule that caused the 75%-empty
 * regression. Reintroducing corpus anchoring here would be a behavioural change
 * to gate behind a prompt-eval, not a free win.
 */
object TagValidator {
    /**
     * Validate [tags] against the [transcript] they came from. Returns the
     * filtered, de-duplicated list preserving order.
     *
     * `transcript` is matched case-insensitively and accent-folded first.
     */
    fun validate(
        tags: List<Tag>,
        transcript: String,
    ): List<Tag> {
        if (tags.isEmpty()) return tags
        // Strip accents from the transcript so an ASCII kebab tag like "perche"
        // can match an accented transcript word like "perché". The user's
        // pronounced "perché", Gemma may emit "perche" (after our `Tag.normalize`
        // strips non-ASCII), and we want them to be considered the same word.
        // This handles all 6 v1 languages — it/es/fr/de/pt all carry accent
        // marks on common words. Normalization runs once per validation call.
        val transcriptFolded = foldForMatching(transcript)

        val out = ArrayList<Tag>(tags.size)
        val seen = HashSet<String>(tags.size)
        for (tag in tags) {
            val value = tag.value
            if (!seen.add(value)) continue
            if (isAnchored(value, transcriptFolded)) {
                out += tag
            }
            // else: silently drop. We don't surface the rejection to the UI —
            // the user has no actionable feedback ("Gemma made up a tag" is
            // a bug, not a thing they can fix), and the missing tag is itself
            // the signal in the rare case they notice.
        }
        return out
    }

    private fun isAnchored(
        tagValue: String,
        transcriptFolded: String,
    ): Boolean {
        // Multi-part kebab tag (e.g. "app-development", "seo-optimization"):
        // require any chunk ≥3 chars to appear as a STANDALONE WORD in the
        // transcript. Word-boundary uses `\p{L}` so Latin accented words work
        // ("café" boundaries cleanly), and substring noise is prevented —
        // "app" doesn't match inside "appuntamento" anymore. Multi-part
        // tags get the strict treatment because their kebab parts are
        // typically common short words ("app", "dev") that easily false-match.
        val parts = tagValue.split('-').filter { it.length >= MIN_PART_LEN }
        val isMultiPart = tagValue.contains('-')
        if (isMultiPart) {
            return parts.any { partOccursAsWord(it, transcriptFolded) }
        }

        // Mono-part tag (a single word like "sogni", "lavoro", "rag"):
        //
        // For ≥4-char mono-part tags we TRUST Gemma. Tags like "lavoro",
        // "personale", "salute", "riflessione" are SEMANTIC ABSTRACTIONS —
        // they describe the topic at a higher level than any literal word
        // in the transcript. A work note legitimately gets "lavoro" even
        // when no "lavor-" word appears; a personal reflection gets
        // "personale" even when nothing in the transcript starts with
        // "pers-". Trying to anchor these lexically is fighting the wrong
        // battle: it produces 75% empty tag arrays (observed in field
        // 2026-05-16), which kills tag-based discoverability — the single
        // worst UX outcome we can ship. Trade-off accepted: ~1-in-20
        // hallucinated 4+ char tag (e.g. "fotografia" on a tech note)
        // slips through; the user can edit it out, while the other 19
        // notes get rich tagging.
        //
        // For ≤3-char mono-part tags we keep the strict word-match. Short
        // tags ("rag", "seo", "ai") are both more likely to be
        // context-bleed hallucinations and more likely to false-match if
        // allowed loose. They need to literally appear.
        if (tagValue.length >= MIN_TRUSTED_LEN) return true
        return partOccursAsWord(tagValue, transcriptFolded)
    }

    /**
     * Returns true when [word] (already lowercase, accent-folded) appears in
     * [haystack] (already accent-folded) as a standalone word — i.e. not
     * inside a longer word like "app" inside "appuntamento".
     */
    private fun partOccursAsWord(
        word: String,
        haystack: String,
    ): Boolean {
        val pattern = "(?<![\\p{L}\\p{M}])${Regex.escape(word)}(?![\\p{L}\\p{M}])"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(haystack)
    }

    /**
     * Lowercase + accent-fold a string for word-matching. Uses Unicode NFD
     * decomposition to split accented chars into base + combining mark, then
     * strips the combining marks. Result: "perché" → "perche", "España" →
     * "Espana", "über" → "uber". The lowercase pass uses `Locale.ROOT` to
     * avoid locale-specific edge cases (Turkish dotless-i, etc.).
     */
    private fun foldForMatching(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return DIACRITIC.replace(nfd, "").lowercase(Locale.ROOT)
    }

    private val DIACRITIC = Regex("\\p{InCombiningDiacriticalMarks}+")

    private const val MIN_PART_LEN = 3

    /**
     * Length threshold above which a mono-part tag is trusted without any
     * lexical anchor. 4 chars is the sweet spot: short tags ("ai", "seo",
     * "rag") still need to literally appear (catches hallucinations);
     * everything ≥4 chars is permitted as a semantic abstraction
     * (catches "lavoro", "personale", "salute", etc.).
     */
    private const val MIN_TRUSTED_LEN = 4
}
