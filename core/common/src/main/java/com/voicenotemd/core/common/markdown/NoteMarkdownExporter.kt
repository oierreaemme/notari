package com.voicenotemd.core.common.markdown

import com.voicenotemd.core.common.domain.Note
import java.time.Instant
import java.time.ZoneId

/**
 * Renders this note as a portable Markdown document with YAML frontmatter — the
 * shape Obsidian, Hugo, Jekyll, LogSeq, Foam and most static-site generators
 * expect. Used by both the ZIP export (`NotesViewModel`) and the single-note
 * share intent (`NoteDetailViewModel`) so both surfaces produce the exact same
 * bytes for a given note.
 *
 * Schema:
 * ```
 * ---
 * title: "..."
 * created: 2026-05-14T...Z
 * updated: 2026-05-14T...Z
 * language: it
 * tags: [tag-a, tag-b]
 * mentions:
 *   - surface: "domani alle 15"
 *     iso: 2026-05-14T15:00:00Z
 * structured: true
 * ---
 *
 * # Title
 *
 * Body markdown...
 * ```
 *
 * The frontmatter exposes the on-device temporal reasoning (the `mentions` block
 * with `surface` → `iso` pairs) so the exported note carries Gemma's structured
 * output, not just a flat string. Tools that ignore YAML frontmatter still render
 * the document correctly thanks to the `# Title` heading and body after `---`.
 *
 * Timestamps are written in the user's LOCAL timezone with an explicit offset
 * (e.g. `2026-05-22T22:41:25+02:00`), not UTC `Z`. We store [Instant]s internally
 * (UTC, correct), but the exported note is something the user reads in Obsidian, and
 * a UTC `Z` value reads as "wrong" because it is offset from the local time the app
 * displays (real-device feedback 2026-05-22). The offset form is still valid ISO-8601
 * and is parsed correctly by Obsidian/Dataview and other front-matter consumers.
 *
 * @param zone the timezone used to render timestamps; defaults to the device zone.
 */
fun Note.toMarkdownWithFrontmatter(zone: ZoneId = ZoneId.systemDefault()): String =
    buildString {
        val displayTitle = title.ifBlank { "Untitled" }
        append("---\n")
        append("title: ").append(yamlQuote(displayTitle)).append('\n')
        append("created: ").append(localIso(createdAt, zone)).append('\n')
        append("updated: ").append(localIso(updatedAt, zone)).append('\n')
        append("language: ").append(language.bcp47).append('\n')
        if (tags.isEmpty()) {
            append("tags: []\n")
        } else {
            append("tags: [")
            append(tags.joinToString(", ") { it.value })
            append("]\n")
        }
        if (mentions.isNotEmpty()) {
            append("mentions:\n")
            mentions.forEach { mention ->
                append("  - surface: ").append(yamlQuote(mention.surfaceForm)).append('\n')
                append("    iso: ")
                val resolved = mention.resolved
                if (resolved != null) append(localIso(resolved, zone)) else append("null")
                append('\n')
            }
        }
        append("structured: ").append(structured).append('\n')
        append("---\n\n")
        append("# ").append(displayTitle).append("\n\n")
        append(bodyMarkdown)
        if (!bodyMarkdown.endsWith('\n')) append('\n')
    }

/**
 * Render an [Instant] as a local ISO-8601 datetime with offset (e.g.
 * `2026-05-22T22:41:25.841+02:00`) in [zone]. Matches the time the app shows the user.
 */
private fun localIso(
    instant: Instant,
    zone: ZoneId,
): String = instant.atZone(zone).toOffsetDateTime().toString()

/**
 * Wrap a string in YAML-safe double quotes, escaping the few characters that
 * matter inside a double-quoted scalar. Keeps surface forms and titles legal
 * even if they contain colons, hashes, newlines, or quotes.
 */
private fun yamlQuote(value: String): String {
    val escaped =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
    return "\"$escaped\""
}
