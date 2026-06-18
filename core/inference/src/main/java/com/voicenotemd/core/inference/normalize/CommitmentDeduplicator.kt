package com.voicenotemd.core.inference.normalize

import java.util.Locale

/**
 * Deterministic remover of the prose↔checkbox duplication (ADR 0015 philosophy: after
 * two prompt rounds — v13's exclusive-survival rule, v14's triple restatement — the
 * 2B-effective model still re-emits a commitment sentence BOTH as prose and as its
 * checkbox in ~2/3 of mixed notes, real-device 2026-06-10 round 4. Omitting a sentence
 * from prose while emitting it elsewhere is global planning the small model can't do
 * reliably; code can).
 *
 * Rule: a prose SENTENCE whose content is already covered by a checkbox is removed.
 * When the prose carried EXTRA information ("…per spostare l'appuntamento") the
 * checkbox text is upgraded to the fuller prose-derived version (marker words like
 * "devo" stripped) before the prose is dropped — so deduplication never loses content,
 * it only removes the second copy.
 *
 * Matching is token-based with a common-prefix tolerance (≥4 chars) because the two
 * copies routinely differ by ASR garbling ("rieffogo" / "rieffilogo", "Luca" /
 * "Lucae") — exact string comparison would miss exactly the cases that matter.
 * Thresholds are conservative: unrelated prose (the "non ricordo se il 20 o il 21"
 * sentence next to a "Controllare la mail" checkbox) shares too few tokens to trip.
 */
object CommitmentDeduplicator {
    fun dedupe(body: String): String {
        if (body.isBlank() || "- [" !in body) return body
        val lines = body.split("\n").toMutableList()

        data class Checkbox(val index: Int, val tokens: List<String>)

        val checkboxes =
            lines.mapIndexedNotNull { i, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")) {
                    val tokens = tokenize(trimmed.substringAfter(']'))
                    if (tokens.size >= MIN_CHECKBOX_TOKENS) Checkbox(i, tokens) else null
                } else {
                    null
                }
            }
        if (checkboxes.isEmpty()) return body

        for (i in lines.indices) {
            val line = lines[i]
            if (line.isBlank() || startsBlock(line)) continue

            val sentences = line.split(SENTENCE_BOUNDARY)
            val kept = mutableListOf<String>()
            for (sentence in sentences) {
                val sentenceTokens = tokenize(sentence)
                val match =
                    checkboxes.firstOrNull { cb ->
                        coverage(cb.tokens, sentenceTokens) >= COVERAGE_THRESHOLD
                    }
                if (match == null || sentenceTokens.isEmpty()) {
                    kept += sentence
                    continue
                }
                // The sentence duplicates a checkbox. If it carries meaningful EXTRA
                // content, upgrade the checkbox text to the fuller version first.
                val extras = contentExtras(sentenceTokens, match.tokens)
                if (extras > MAX_FREE_EXTRAS) {
                    val indent = lines[match.index].substringBefore("- [")
                    val marker = lines[match.index].trimStart().substring(0, 5) // "- [ ]" / "- [x]"
                    lines[match.index] = "$indent$marker " + upgradeText(sentence)
                }
                // Drop the prose copy.
            }
            lines[i] = kept.joinToString(" ").trim()
        }

        return lines.joinToString("\n")
    }

    /** Fraction of checkbox tokens found (exact or prefix-matched) in the sentence. */
    private fun coverage(
        checkboxTokens: List<String>,
        sentenceTokens: List<String>,
    ): Double {
        if (checkboxTokens.isEmpty()) return 0.0
        val matched = checkboxTokens.count { cb -> sentenceTokens.any { tokensMatch(cb, it) } }
        return matched.toDouble() / checkboxTokens.size
    }

    /** Content tokens of the sentence not covered by the checkbox (markers/stopwords excluded). */
    private fun contentExtras(
        sentenceTokens: List<String>,
        checkboxTokens: List<String>,
    ): Int =
        sentenceTokens.count { s ->
            s !in MARKER_WORDS && s !in STOP_WORDS && checkboxTokens.none { tokensMatch(it, s) }
        }

    /** Strip commitment markers from the sentence so it reads as a checkbox item. */
    private fun upgradeText(sentence: String): String {
        var s = sentence.trim().trimEnd('.', ';', ',')
        for (marker in MARKER_PHRASES) {
            s = s.replace(marker, " ")
        }
        return s.replace(MULTI_SPACE, " ").trim().replaceFirstChar { it.uppercase() }
    }

    private fun tokensMatch(
        a: String,
        b: String,
    ): Boolean =
        a == b ||
            (minOf(a.length, b.length) >= PREFIX_MIN && a.commonPrefixWith(b).length >= PREFIX_MIN)

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace("**", "")
            .split(NON_ALNUM)
            .filter { it.isNotEmpty() && it !in MARKER_WORDS }

    private fun startsBlock(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("- ") || t.startsWith("* ") || t.startsWith("#") || t.startsWith("> ")
    }

    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?;])\\s+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")

    /** Commitment markers — excluded from token comparison and stripped on upgrade. */
    private val MARKER_WORDS =
        setOf(
            "devo", "davo", "dovrei", "ricordarmi", "ricordare", "dimenticare",
            "need", "needs", "should", "must", "dois", "muss", "preciso", "hay", "que",
        )

    private val MARKER_PHRASES =
        listOf(
            Regex("(?i)\\b(devo|davo|dovrei) (ricordarmi|ricordare) (di|che)\\b"),
            Regex("(?i)\\bnon (devo|posso) dimenticare (di|che)\\b"),
            Regex("(?i)\\b(devo|davo|dovrei)\\b"),
            Regex("(?i)\\bi (need|have) to\\b"),
            Regex("(?i)\\bi (should|must)\\b"),
        )

    /** Function words that don't count as "extra information" carried by the prose. */
    private val STOP_WORDS =
        setOf(
            "di", "il", "la", "le", "lo", "i", "gli", "a", "al", "alla", "alle", "per",
            "e", "che", "un", "una", "uno", "non", "poi", "con", "in", "su", "da", "del",
            "della", "comunque", "allora",
            "the", "a", "an", "to", "on", "about", "with", "and", "of", "for", "at",
        )

    private const val COVERAGE_THRESHOLD = 0.8
    private const val MAX_FREE_EXTRAS = 2
    private const val MIN_CHECKBOX_TOKENS = 3
    private const val PREFIX_MIN = 4
}
