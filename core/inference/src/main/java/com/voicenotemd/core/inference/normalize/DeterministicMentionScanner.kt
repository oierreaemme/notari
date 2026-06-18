package com.voicenotemd.core.inference.normalize

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Deterministic BACKSTOP for datetime mentions (review 2026-06-10): when Gemma emits an
 * empty `mentions` array, scan the transcript itself for unambiguous datetime
 * references and resolve them with the same deterministic machinery the override layer
 * uses ([RelativeDateTimeResolver]).
 *
 * Why: on-device eval showed the model occasionally dropping a clearly present mention
 * ("entro venerdì", "on Monday morning") while the transcript contained it verbatim.
 * The scanner never invents — every surface form it returns is a literal substring of
 * the transcript, and the resolution is the same pure function of (clock, zone) that
 * ADR 0015 already trusts.
 *
 * Scope is deliberately narrow:
 *  - only FUTURE-ORIENTED table phrases ([RelativeDateTimeResolver.scannablePhrases]):
 *    "oggi"/"ieri"-class words appear constantly in narration without being
 *    schedulable, so they are never scanned;
 *  - a weekday match is dropped when adjacent to a past modifier ("venerdì scorso",
 *    "last friday");
 *  - numeric clock times ("alle 15", "at 9:30", "às 14") are recognized; when one
 *    immediately follows a day anchor the two merge into a single compound mention
 *    ("domani alle 15" → tomorrow 15:00); a standalone time resolves to its next
 *    future occurrence via [RelativeDateTimeResolver.biasToFuture];
 *  - at most [MAX_MENTIONS] results, first occurrences win.
 *
 * It runs ONLY when the model produced no mentions at all — when the model did emit
 * some, its judgment (including deliberate omissions) is respected.
 */
object DeterministicMentionScanner {
    data class ScannedMention(val surfaceForm: String, val resolved: Instant)

    fun scan(
        transcript: String,
        languageBcp47: String?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScannedMention> {
        if (transcript.isBlank()) return emptyList()
        // NFC-normalize before matching: whisper can emit accents in decomposed form
        // ("venerdi" + combining grave), which would silently miss the composed-form
        // table phrases (suspected cause of a dropped "venerdì", round 4 2026-06-10).
        // Surfaces are substrings of the normalized text — NFC is also the display form.
        val text = Normalizer.normalize(transcript, Normalizer.Form.NFC)
        val lower = text.lowercase(Locale.ROOT)

        // 1. Day-anchor matches from the future-oriented phrase tables, longest first
        //    so "domani sera" wins over "domani" on the same span.
        val dayMatches = mutableListOf<Span>()
        val phrases =
            RelativeDateTimeResolver.scannablePhrases(languageBcp47)
                .sortedByDescending { it.length }
        for (phrase in phrases) {
            val regex = Regex("(?<![\\p{L}])${Regex.escape(phrase)}(?![\\p{L}])")
            for (match in regex.findAll(lower)) {
                val range = match.range
                if (dayMatches.any { it.overlaps(range) }) continue
                if (hasAdjacentPastModifier(lower, range)) continue
                val resolved =
                    RelativeDateTimeResolver.resolve(phrase, languageBcp47, now, zone)
                        ?: continue
                dayMatches += Span(range, resolved)
            }
        }

        // 2. Numeric clock times ("alle 15", "at 9:30"). Merged into the preceding day
        //    anchor when contiguous; standalone otherwise (next future occurrence).
        val timeMatches = mutableListOf<Span>()
        for (match in TIME_REGEX.findAll(lower)) {
            val hour = match.groupValues[2].toIntOrNull() ?: continue
            if (hour > 23) continue
            val minute = match.groupValues[4].toIntOrNull() ?: 0
            if (minute > 59) continue
            val range = match.range

            val day = dayMatches.firstOrNull { it.isImmediatelyBefore(range, lower) }
            if (day != null) {
                // Compound: "domani alle 15" — date of the anchor, time from the match.
                val local =
                    LocalDateTime.ofInstant(day.resolved, zone)
                        .toLocalDate()
                        .atTime(hour, minute)
                day.expandTo(range.last, local.atZone(zone).toInstant())
            } else {
                if (timeMatches.any { it.overlaps(range) }) continue
                val todayAt =
                    LocalDateTime.ofInstant(now, zone)
                        .toLocalDate()
                        .atTime(hour, minute)
                        .atZone(zone)
                        .toInstant()
                val surface = text.substring(range.first, range.last + 1)
                timeMatches +=
                    Span(range, RelativeDateTimeResolver.biasToFuture(todayAt, surface, now, zone))
            }
        }

        return (dayMatches + timeMatches)
            .sortedBy { it.start }
            .take(MAX_MENTIONS)
            .map { span ->
                ScannedMention(
                    surfaceForm = text.substring(span.start, span.end + 1),
                    resolved = span.resolved,
                )
            }
    }

    /** True when the word immediately before or after [range] marks the day as past. */
    private fun hasAdjacentPastModifier(
        lower: String,
        range: IntRange,
    ): Boolean {
        val before = lower.substring(0, range.first).takeLastWordOrNull()
        val after = lower.substring(range.last + 1).takeFirstWordOrNull()
        return (before != null && before in PAST_MODIFIERS) ||
            (after != null && after in PAST_MODIFIERS)
    }

    private fun String.takeLastWordOrNull(): String? = trimEnd().split(NON_LETTER).lastOrNull { it.isNotEmpty() }

    private fun String.takeFirstWordOrNull(): String? = trimStart().split(NON_LETTER).firstOrNull { it.isNotEmpty() }

    /** Mutable span: compound merging extends a day anchor over its adjacent time. */
    private class Span(range: IntRange, var resolved: Instant) {
        val start: Int = range.first
        var end: Int = range.last
            private set

        fun overlaps(other: IntRange): Boolean = start <= other.last && other.first <= end

        fun isImmediatelyBefore(
            other: IntRange,
            text: String,
        ): Boolean =
            other.first > end &&
                other.first - end <= MAX_GAP_CHARS &&
                text.substring(end + 1, other.first).isBlank()

        fun expandTo(
            newEnd: Int,
            newResolved: Instant,
        ) {
            end = newEnd
            resolved = newResolved
        }
    }

    /**
     * "<prep> H[:MM]" in the v1 languages: alle/at/a las/à/um/às. The preposition is
     * required — a bare number ("ho comprato 3 mele") must never become a time.
     */
    private val TIME_REGEX =
        Regex(
            "(?<![\\p{L}\\d])(alle|at|a las|à|um|às)\\s+(\\d{1,2})([:.](\\d{2}))?(?!\\d)",
            RegexOption.IGNORE_CASE,
        )

    private val NON_LETTER = Regex("[^\\p{L}]+")

    private val PAST_MODIFIERS =
        setOf(
            "scorso", "scorsa", "last", "pasado", "pasada",
            "dernier", "dernière", "letzten", "letzte", "passado", "passada",
        )

    private const val MAX_GAP_CHARS = 2
    private const val MAX_MENTIONS = 3
}
