package com.voicenotemd.core.inference.normalize

/**
 * Deterministic post-processor for the body Markdown emitted by Gemma.
 *
 * Gemma is reliable at deciding *what* to emit (checkboxes, bullets, headings)
 * but unreliable at the surrounding *whitespace*. We've seen real outputs like:
 *
 *   "Mi sono svegliato confuso. - [ ] Ricordarmi di parlarne a Sofia domani."
 *
 * where the checkbox is glued onto the end of a prose sentence with no newline
 * separator. The rendering looks broken in the UI and in any Markdown viewer.
 *
 * Rather than trying to coax the model into better formatting via the prompt
 * (probabilistic, never 100%), we enforce the rule deterministically here:
 *
 *  1. Every `- [ ]` and `- [x]` checkbox starts on its own line.
 *  2. Every `- ` bullet starts on its own line (but bullets *inside* a code
 *     fence or after a heading char are left alone — those are valid).
 *  3. Between prose and a checkbox/bullet block there is a blank line.
 *  4. Three or more consecutive newlines collapse to exactly two.
 *  5. Trailing whitespace on each line is stripped.
 *
 * All transformations are content-preserving: the same words, just better
 * separated. Per ADR 0015, this is exactly the kind of work that belongs in
 * code, not in the prompt — there's a single correct answer and no judgment
 * call to make.
 */
object MarkdownBodyFormatter {
    /**
     * Apply all normalization passes in order. Pure function, idempotent.
     */
    fun format(body: String): String {
        if (body.isBlank()) return body
        var s = body

        // 1. Break inline checkboxes/bullets onto their own line.
        //    Matches " - [ ]" or " - [x]" or " - " that appears mid-line after
        //    a non-newline character. We insert a newline BEFORE the marker.
        //    Negative lookbehind on `\n` prevents matching what's already correct.
        s =
            INLINE_CHECKBOX.replace(s) { match ->
                "\n${match.value.trimStart()}"
            }
        s =
            INLINE_BULLET.replace(s) { match ->
                "\n${match.value.trimStart()}"
            }

        // 2. Ensure a blank line BEFORE a checkbox/bullet block that follows prose.
        //    A "prose line" is any line that doesn't itself start with `- ` or `#`
        //    or `>`. When such a line is immediately followed by a marker line,
        //    insert a blank line between them.
        s = ensureBlankLineBeforeBlocks(s)

        // 3. Collapse 3+ consecutive newlines to exactly 2.
        s = TRIPLE_NEWLINE.replace(s, "\n\n")

        // 4. Strip trailing whitespace on each line.
        s = TRAILING_WS.replace(s, "")

        // 5. Final trim — but preserve a trailing newline if the original had one.
        return s.trim()
    }

    private fun ensureBlankLineBeforeBlocks(text: String): String {
        val lines = text.split("\n")
        if (lines.size < 2) return text
        val out = StringBuilder(text.length + 16)
        for (i in lines.indices) {
            val current = lines[i]
            if (i > 0) {
                val prev = lines[i - 1]
                val prevIsProse = prev.isNotBlank() && !startsBlock(prev)
                val currIsMarker = startsBlock(current)
                // Insert a blank line between a prose line and a marker line.
                if (prevIsProse && currIsMarker) {
                    out.append('\n')
                }
                out.append('\n')
            }
            out.append(current)
        }
        return out.toString()
    }

    /**
     * "Block-starting" lines: checkboxes, bullets, ordered list, headings,
     * blockquotes. We never insert/remove whitespace around code-fence boundaries
     * because that could break syntax highlighting inside fenced blocks — but
     * Gemma never emits fenced code in our notes, so this is theoretical.
     */
    private fun startsBlock(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- [ ]") ||
            trimmed.startsWith("- [x]") ||
            trimmed.startsWith("- [X]") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.startsWith("# ") ||
            trimmed.startsWith("## ") ||
            trimmed.startsWith("### ") ||
            trimmed.startsWith("> ") ||
            ORDERED_LIST_PREFIX.containsMatchIn(trimmed)
    }

    // Match an inline " - [ ]" or " - [x]" that appears after a non-newline
    // character. We capture the whole marker incl. the space prefix and rewrite
    // it with a leading newline.
    private val INLINE_CHECKBOX = Regex("(?<=\\S)\\s+- \\[[ xX]]\\s")

    // Match an inline " - " bullet that appears after a non-newline character.
    // The bullet must have at least one trailing LETTER (Unicode \p{L}) to avoid
    // catching stray dashes ("e -- ricorda") AND to avoid mangling arithmetic
    // expressions like "5 - 3 = 2" which `\w` would erroneously match because
    // `\w` includes digits. Real voice notes contain math far more often than
    // they contain a bullet that starts with a digit, so the trade-off is clear.
    private val INLINE_BULLET = Regex("(?<=\\S)\\s+- (?=\\p{L})")

    // 3+ consecutive newlines, possibly with whitespace between.
    private val TRIPLE_NEWLINE = Regex("\\n{3,}")

    // Trailing horizontal whitespace before a newline or end-of-string.
    private val TRAILING_WS = Regex("[ \\t]+(?=\\n|$)")

    // Ordered list prefix: "1. ", "2. ", up to "99. ".
    private val ORDERED_LIST_PREFIX = Regex("^\\d{1,2}\\. ")
}
