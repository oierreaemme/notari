package com.voicenotemd.core.inference.prompt

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Loads a versioned prompt template from the module's assets/prompts/ directory.
 *
 * Why a class instead of an object: we want this to be Hilt-injectable with a [Context],
 * which makes Robolectric / instrumentation tests easier and avoids static
 * `Application.getInstance()`-style anti-patterns.
 */
class AssetPromptLoader(private val context: Context) {
    fun load(name: String): PromptTemplate {
        val text =
            context.assets.open("prompts/$name").use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        return StaticPromptTemplate(text)
    }

    companion object {
        /**
         * v8 fixes the "event description dropped under `##` heading" regression
         * caught in real-device traces 2026-05-16. When the user dictated
         * *"riunione con Marco e il team di Atlassian alle 15:30 per parlare della
         * migrazione di Jira. Devo preparare le slide…"*, v7 produced `## Riunione
         * Jira` followed directly by the action checkboxes — dropping the entire
         * event description (with whom, what about). Gemma was conflating the
         * heading with the description.
         *
         * v8 adds:
         *   - A new top-level `Headings — REQUIRED rule` that explicitly states
         *     the heading is a topic LABEL and never replaces the event prose.
         *   - Worked Example 10 showing two `##` topics where the first carries
         *     a full event description in prose between the heading and the
         *     checkboxes; the second is description-free because the heading
         *     itself captures the event.
         *
         * v7 narrows the prompt's responsibility to *semantic* judgment only, after
         * we moved formatting/date/tag normalization into deterministic post-processing
         * (see ADR 0015). What changed vs v6:
         *   - Sharpened the "Checkboxes vs prose" distinction: a `devo` ANYWHERE
         *     in the transcript = checkbox, even if buried inside a continuous
         *     prose paragraph (was being missed in real notes); an EVENT (riunione,
         *     appuntamento) is NOT a checkbox even when described next to commitments
         *     (was being over-extracted).
         *   - Strengthened the "no smoothing" rule with worked Example 9 showing
         *     meta-speech ("Aspetta, c'era qualcos'altro... non ricordo") preserved
         *     verbatim instead of being rewritten as a clean "devo ricordarmi" commitment.
         *   - Added Example 8 demonstrating multi-checkbox extraction from a single
         *     paragraph with three scattered `devo` markers.
         *   - REMOVED the checkbox/bullet whitespace rule — `MarkdownBodyFormatter`
         *     handles all line breaks deterministically now, so the prompt doesn't
         *     have to coax the model into newline discipline anymore.
         *   - The `surface_form` precision rule was sharpened ("alle 14" not "14"),
         *     and `RelativeDateTimeResolver` overrides simple relative expressions
         *     across all 6 languages anyway.
         *
         * v6 calibrates the line between "transform" and "invent" on orthographic
         * cleanup. v5 (and earlier) said "preserve meaning" without ever explicitly
         * permitting the model to fix obvious typos — so the model interpreted "no
         * invention" maximally and preserved every misspelling and ASR garbage token
         * verbatim. v6 adds a REQUIRED `Cleanup` section that:
         *   - permits fixing clear orthographic errors and word-segmentation breakage
         *     ("integrale rno" → "intorno", "arrabbia" → "arrabbiata")
         *   - explicitly KEEPS irrecoverable garbled fragments verbatim
         *     ("hluba chicca", "oasi o tetta") — invention is worse than mess
         *   - explicitly PRESERVES proper nouns even when unusual ("Carusi",
         *     "Lakia", "Remo reale")
         *   - reaffirms the "no rephrase, no smooth-out style" rule
         * Worked Example 7 demonstrates all three behaviours on a single transcript.
         * See ADR 0014.
         *
         * v5 added tag-consistency pressure via `EXISTING TAGS`. See ADR 0012.
         *
         * v4 fixed four E2B-specific failure modes observed on real Gemma 4 E2B output
         * (logs captured 2026-05-13 against v3):
         *   1. The `mentions` array was collecting person/place names (e.g. "Sarah",
         *      "cliente Rossi") instead of just datetime references. v4 added an
         *      EXCLUSIVE "DATETIME REFERENCES ONLY" rule with explicit anti-examples.
         *   2. `- [ ]` was never emitted for "devo / I need to / must" tasks. v4 flipped
         *      the framing from "ONLY when X" to "REQUIRED — every time you see X",
         *      which the 2B-effective model respects more reliably.
         *   3. Shopping lists were being collapsed into comma-separated prose. v4 added
         *      a REQUIRED rule for enumerations and an explicit bullet-list example.
         *   4. `##` headings were never used on multi-topic notes. v4 added an explicit
         *      heading rule with a multi-topic example.
         *
         * Earlier prompts stay in assets/ as legacy. To roll back, point this constant
         * at the prior version.
         */
        const val ACTIVE_PROMPT = "structure_note_v8.txt"
    }
}
