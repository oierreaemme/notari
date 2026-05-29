package com.voicenotemd.feature.notedetail

import com.voicenotemd.core.common.domain.Note

data class NoteDetailUiState(
    val isLoading: Boolean = true,
    val note: Note? = null,
    val isEditing: Boolean = false,
    val draftTitle: String = "",
    val draftBody: String = "",
    val notFound: Boolean = false,
    /** True while the note's text is being (re)structured on-device by Gemma. */
    val isRestructuring: Boolean = false,
    /** A user-facing message when a restructure attempt didn't produce a structured note. */
    val restructureError: String? = null,
)

sealed interface NoteDetailUiIntent {
    data object EnterEdit : NoteDetailUiIntent

    data object CancelEdit : NoteDetailUiIntent

    data object SaveEdit : NoteDetailUiIntent

    data class UpdateDraftTitle(val text: String) : NoteDetailUiIntent

    data class UpdateDraftBody(val text: String) : NoteDetailUiIntent

    data object Delete : NoteDetailUiIntent

    data object Share : NoteDetailUiIntent

    data object AppendVoice : NoteDetailUiIntent

    /**
     * Re-run Gemma structuring on the note's current text. The primary use is a note that
     * was saved as plain text because structuring failed at capture time (e.g. a cold,
     * screen-off device timed out) — the user can retry later when the device is warm.
     * Also available for already-structured notes as a "redo". See ADR 0022 follow-up.
     */
    data object Restructure : NoteDetailUiIntent

    data object DismissRestructureError : NoteDetailUiIntent
}

sealed interface NoteDetailUiEvent {
    /**
     * Caller-side: open the OS share sheet with [markdown] as `text/markdown`. The screen
     * routes this through an `ACTION_SEND` intent — the file never touches the disk.
     */
    data class ShareMarkdown(val title: String, val markdown: String) : NoteDetailUiEvent

    /** The note was deleted — callers should pop back to the list. */
    data object Closed : NoteDetailUiEvent

    /** Naviga alla route di cattura passando l'ID per appendere l'audio. */
    data class NavigateToAppend(val noteId: String) : NoteDetailUiEvent
}
