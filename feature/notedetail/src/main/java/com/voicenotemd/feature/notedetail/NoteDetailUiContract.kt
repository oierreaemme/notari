package com.voicenotemd.feature.notedetail

import com.voicenotemd.core.common.domain.Note

data class NoteDetailUiState(
    val isLoading: Boolean = true,
    val note: Note? = null,
    val isEditing: Boolean = false,
    val draftTitle: String = "",
    val draftBody: String = "",
    val notFound: Boolean = false,
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
