package com.voicenotemd.feature.notes

import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag

/**
 * MVI surface for the notes list. Per ADR 0006 the structure is fixed:
 * - [NotesUiState] is the single screen state.
 * - [NotesUiIntent] is the single intent funnel (search, tag filter, refresh).
 * - One-shot effects flow through the ViewModel's `uiEvents` SharedFlow.
 */
data class NotesUiState(
    val isLoading: Boolean = true,
    val notes: List<Note> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val activeTag: Tag? = null,
    val query: String = "",
    val selectedNoteIds: Set<String> = emptySet(),
    /**
     * `true` while the destructive "delete selected notes" confirmation dialog is
     * visible. Gated by an explicit confirmation step (CLAUDE.md sez. 8: dialogs are
     * reserved for destructive actions) so a stray tap on the trash icon doesn't
     * wipe a multi-select set the user spent time assembling.
     */
    val showDeleteSelectedConfirm: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedNoteIds.isNotEmpty()
}

sealed interface NotesUiIntent {
    data class UpdateQuery(val text: String) : NotesUiIntent

    data class SelectTag(val tag: Tag?) : NotesUiIntent

    data class ToggleSelection(val noteId: String) : NotesUiIntent

    data object SelectAll : NotesUiIntent

    data object ClearSelection : NotesUiIntent

    data object RequestExport : NotesUiIntent

    data class ExportToZip(val uri: android.net.Uri) : NotesUiIntent

    /** User tapped the trash icon while in selection mode → show the confirm dialog. */
    data object RequestDeleteSelected : NotesUiIntent

    /** User confirmed the destructive action in the dialog → perform the delete. */
    data object ConfirmDeleteSelected : NotesUiIntent

    /** User dismissed the dialog without confirming → hide it, keep the selection. */
    data object DismissDeleteSelected : NotesUiIntent
}

sealed interface NotesUiEvent {
    data object TriggerZipPicker : NotesUiEvent

    data class ExportCompleted(val message: String) : NotesUiEvent

    /**
     * Emitted after the multi-select delete confirmation has been processed.
     * The Route uses this to surface a snackbar so the user has feedback that
     * the destructive action actually happened.
     */
    data class SelectionDeleted(val count: Int) : NotesUiEvent
}
