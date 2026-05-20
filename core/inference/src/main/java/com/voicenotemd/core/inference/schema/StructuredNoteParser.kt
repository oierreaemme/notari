package com.voicenotemd.core.inference.schema

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.voicenotemd.core.common.domain.RawDateMention
import com.voicenotemd.core.common.domain.StructuredNote
import com.voicenotemd.core.common.result.DomainResult

/**
 * Parses the model's raw response into a [StructuredNote] domain object.
 *
 * Real Gemma output is messy: occasional code fences (```json), trailing prose, leading
 * BOMs. We strip these before letting Moshi parse. If structural validation fails the
 * caller is expected to retry once with a stricter prompt and then fall back to plain
 * text — this is enforced by [StructureNoteUseCase] (to be added).
 */
class StructuredNoteParser(
    moshi: Moshi = defaultMoshi,
) {
    private val adapter = moshi.adapter(StructuredNoteJson::class.java).lenient()

    fun parse(raw: String): DomainResult<StructuredNote, ParseError> {
        val cleaned = sanitize(raw) ?: return DomainResult.Failure(ParseError.NoJsonObject)
        val json =
            try {
                adapter.fromJson(cleaned) ?: return DomainResult.Failure(ParseError.EmptyJson)
            } catch (e: Exception) {
                return DomainResult.Failure(ParseError.MalformedJson(e.message ?: "unknown"))
            }

        // Required fields — if any of these are missing the model didn't fulfill the contract.
        val title =
            json.title?.trim().orEmpty().ifEmpty {
                return DomainResult.Failure(ParseError.MissingField("title"))
            }
        val body =
            json.bodyMarkdown?.trim().orEmpty().ifEmpty {
                return DomainResult.Failure(ParseError.MissingField("body_markdown"))
            }

        return DomainResult.Success(
            StructuredNote(
                title = title.take(MAX_TITLE_LEN),
                bodyMarkdown = body,
                tags =
                    json.tags
                        ?.filterNotNull()
                        ?.map(String::trim)
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                mentions =
                    json.mentions
                        ?.filterNotNull()
                        ?.mapNotNull { m ->
                            val surface = m.surfaceForm?.trim().orEmpty()
                            if (surface.isEmpty()) {
                                null
                            } else {
                                RawDateMention(surface, m.isoResolved?.trim())
                            }
                        }
                        .orEmpty(),
                languageBcp47 = json.language?.trim().orEmpty(),
            ),
        )
    }

    /**
     * Strip the most common forms of model garbage around a JSON object:
     * - leading/trailing whitespace and BOMs
     * - markdown code fences (```json ... ```)
     * - in-band reasoning-trace tags (`<thought>...</thought>`, also `<think>` and
     *   `<thinking>`) that Gemma 4's Thinking Mode can emit alongside the structured
     *   output. The strip happens BEFORE the brace scan so JSON-like content inside
     *   the reasoning ("I'll return {\"title\":...}") can't fool the first-brace /
     *   last-brace heuristic.
     * - leading "Here is the JSON:" type preambles by clipping to the first `{` and last `}`
     *
     * Returns null if no JSON object can be located.
     */
    internal fun sanitize(raw: String): String? {
        val noBom = raw.trim().removePrefix("").trim()
        val noFence =
            noBom
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
        val noThoughts = stripReasoningTags(noFence)
        val firstBrace = noThoughts.indexOf('{')
        val lastBrace = noThoughts.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null
        val jsonBlock = noThoughts.substring(firstBrace, lastBrace + 1)
        return escapeRawNewlinesInStrings(jsonBlock)
    }

    /**
     * Remove well-formed reasoning-trace blocks emitted by Gemma 4 in Thinking Mode.
     *
     * Three independent confirmations from competitor analysis (2026-05-18:
     * DiagramFlowAI, HumanLayer, and an AI Studio observation surfaced in the
     * dev.to community) established that Gemma 4 surfaces its reasoning chain as
     * in-band tags rather than via a separate SDK channel. The literal tag name
     * varies — `<thought>` is the canonical form, but `<think>` and `<thinking>`
     * appear in related model families and we strip all three defensively.
     *
     * We run three independent regexes — one per tag name — rather than a single
     * pattern with alternation + backreference. This guarantees we never strip a
     * mismatched pair like `<thought>...</think>` (each regex only matches its own
     * open/close literal), and it sidesteps a JVM regex subtlety where the
     * case-insensitivity of `\1` is documented ambiguously between releases. Each
     * regex is case-insensitive (so `<THOUGHT>...</thought>` is handled),
     * dot-matches-newlines (so multi-line reasoning is fully captured), and
     * non-greedy (so consecutive blocks are handled independently rather than
     * swallowed into one).
     *
     * Truncated traces (opening tag without a closing tag — would happen if the
     * model hits its token budget mid-reasoning and never emits the JSON) are
     * deliberately left in place. In that scenario there is no JSON anyway, so
     * the downstream brace-scan correctly returns `null` and we fall back to
     * plain text per ADR 0005.
     */
    private fun stripReasoningTags(s: String): String {
        var result = s
        for (regex in REASONING_TAG_REGEXES) {
            result = regex.replace(result, "")
        }
        return result.trim()
    }

    /**
     * Edge LLMs like Gemma E2B often fail to escape newlines inside JSON strings,
     * producing invalid JSON that crashes Moshi. This repairs raw \n and \r inside strings.
     */
    private fun escapeRawNewlinesInStrings(json: String): String {
        val sb = java.lang.StringBuilder(json.length + 16)
        var inString = false
        var isEscaped = false
        for (c in json) {
            when (c) {
                '"' -> {
                    if (!isEscaped) inString = !inString
                    sb.append(c)
                    isEscaped = false
                }
                '\\' -> {
                    sb.append(c)
                    isEscaped = !isEscaped
                }
                '\n' -> {
                    if (inString) {
                        sb.append("\\n")
                    } else {
                        sb.append(c)
                    }
                    isEscaped = false
                }
                '\r' -> {
                    if (inString) {
                        sb.append("\\r")
                    } else {
                        sb.append(c)
                    }
                    isEscaped = false
                }
                else -> {
                    sb.append(c)
                    isEscaped = false
                }
            }
        }
        return sb.toString()
    }

    sealed interface ParseError {
        data object NoJsonObject : ParseError

        data object EmptyJson : ParseError

        data class MalformedJson(val reason: String) : ParseError

        data class MissingField(val name: String) : ParseError
    }

    companion object {
        const val MAX_TITLE_LEN = 60

        val defaultMoshi: Moshi =
            Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

        /**
         * One regex per supported tag name: `<thought>...</thought>`,
         * `<think>...</think>`, `<thinking>...</thinking>`. Three separate patterns
         * (rather than one pattern with alternation) is the simplest construction
         * that cannot match a mismatched pair like `<thought>...</think>`. Compiled
         * once at class load — see [stripReasoningTags].
         */
        private val REASONING_TAG_REGEXES: List<Regex> =
            listOf("thought", "think", "thinking").map { tag ->
                Regex(
                    "<$tag>.*?</$tag>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
            }
    }
}
