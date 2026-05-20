package com.voicenotemd.core.common.markdown

import com.voicenotemd.core.common.domain.Note

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
 */
fun Note.toMarkdownWithFrontmatter(): String =
    buildString {
        val displayTitle = title.ifBlank { "Untitled" }
        append("---\n")
        append("title: ").append(yamlQuote(displayTitle)).append('\n')
        append("created: ").append(createdAt).append('\n')
        append("updated: ").append(updatedAt).append('\n')
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
                if (mention.resolved != null) append(mention.resolved.toString()) else append("null")
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
