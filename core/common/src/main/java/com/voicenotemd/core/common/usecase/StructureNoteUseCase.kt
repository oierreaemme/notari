package com.voicenotemd.core.common.usecase

import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note

/**
 * Transforms a raw transcript into a [Note], using the on-device LLM.
 *
 * Implemented in :core:inference. The interface lives in :core:common so feature
 * code and the ViewModel can depend on it without pulling LiteRT-LM into their
 * compile classpath.
 *
 * The contract is intentionally infallible from the caller's perspective:
 * - On success, returns a [StructuringResult] with `note.structured == true`.
 * - When structuring fails (model OOM, malformed JSON twice in a row, etc.) the
 *   implementation falls back to a plain-text [Note] (`structured = false`) per
 *   ADR 0005. The caller never has to handle a model error itself. In that case
 *   [StructuringResult.lastRawResponse] carries whatever the model emitted on the
 *   final attempt — so the UI (and the developer) can surface it in a debug card
 *   for diagnosis instead of guessing why JSON parsing failed.
 *
 * The [forceLanguage] parameter overrides the model's language detection. Use
 * this when the user has pinned a specific language in Settings.
 */
interface StructureNoteUseCase {
    /**
     * Structure a raw transcript into a [Note].
     *
     * @param existingTags the tag corpus already in use across the user's saved notes.
     *   Passed verbatim to Gemma as prompt context with an instruction to REUSE one
     *   of them when it fits the new note's topic, rather than coining a near-synonym
     *   ("app" vs "app-development" for the same subject). Empty list = first note,
     *   no consistency pressure. The implementation may cap to a top-N to protect
     *   the prompt token budget.
     */
    suspend operator fun invoke(
        transcript: String,
        forceLanguage: Language? = null,
        existingTags: List<String> = emptyList(),
    ): StructuringResult

    /**
     * Eagerly load the inference engine if it isn't already in memory. Idempotent.
     * Designed to be fire-and-forget from feature ViewModels' init blocks — when the
     * user lands on the capture screen, this kicks off the ~1.5 GB engine load in the
     * background. By the time the user finishes dictating and submits the transcript,
     * the first call to [invoke] hits a warm engine instead of paying the 15-30s
     * cold-start latency.
     *
     * Default no-op so test doubles and the plain-text-fallback path don't have to
     * implement it.
     */
    suspend fun warmUp() = Unit
}

data class StructuringResult(
    val note: Note,
    /**
     * Raw text the inference engine returned on the **final** attempt. `null` when
     * structuring succeeded on the first pass and we don't need to surface debug
     * data; non-null whenever `note.structured == false`, and may also be non-null
     * after a successful retry — useful for prompt-quality investigations.
     */
    val lastRawResponse: String?,
)
