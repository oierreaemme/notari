package com.voicenotemd.feature.notedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.common.markdown.toMarkdownWithFrontmatter
import com.voicenotemd.core.common.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Holds and edits a single note. Reads through the [NoteRepository] flow so external edits
 * (e.g. from an automated test) reflect into the screen.
 *
 * The `noteId` is taken from the navigation arguments via [SavedStateHandle].
 */
@HiltViewModel
class NoteDetailViewModel
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        /** Pluggable for tests; production uses [Clock.systemUTC]. */
        internal var clock: Clock = Clock.systemUTC()

        private val noteId: String =
            requireNotNull(savedStateHandle[NOTE_ID_KEY]) {
                "noteId must be passed as a navigation argument under key '$NOTE_ID_KEY'"
            }

        private val _uiState = MutableStateFlow(NoteDetailUiState())
        val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

        private val _uiEvents = MutableSharedFlow<NoteDetailUiEvent>(extraBufferCapacity = 4)
        val uiEvents: SharedFlow<NoteDetailUiEvent> = _uiEvents.asSharedFlow()

        init {
            viewModelScope.launch {
                noteRepository.observe(noteId).collect { note ->
                    _uiState.update { current ->
                        when {
                            note == null -> current.copy(isLoading = false, notFound = true)
                            current.isEditing -> current.copy(isLoading = false, note = note)
                            else ->
                                current.copy(
                                    isLoading = false,
                                    note = note,
                                    draftTitle = note.title,
                                    draftBody = note.bodyMarkdown,
                                )
                        }
                    }
                }
            }
        }

        fun onIntent(intent: NoteDetailUiIntent) {
            when (intent) {
                NoteDetailUiIntent.EnterEdit ->
                    _uiState.update {
                        val note = it.note ?: return@update it
                        it.copy(isEditing = true, draftTitle = note.title, draftBody = note.bodyMarkdown)
                    }
                NoteDetailUiIntent.CancelEdit ->
                    _uiState.update {
                        val note = it.note ?: return@update it.copy(isEditing = false)
                        it.copy(isEditing = false, draftTitle = note.title, draftBody = note.bodyMarkdown)
                    }
                NoteDetailUiIntent.SaveEdit -> handleSaveEdit()
                is NoteDetailUiIntent.UpdateDraftTitle ->
                    _uiState.update { it.copy(draftTitle = intent.text) }
                is NoteDetailUiIntent.UpdateDraftBody ->
                    _uiState.update { it.copy(draftBody = intent.text) }
                NoteDetailUiIntent.Delete -> handleDelete()
                NoteDetailUiIntent.Share -> handleShare()
                NoteDetailUiIntent.AppendVoice -> {
                    viewModelScope.launch {
                        _uiEvents.emit(NoteDetailUiEvent.NavigateToAppend(noteId))
                    }
                }
            }
        }

        private fun handleSaveEdit() {
            val state = _uiState.value
            val note = state.note ?: return
            val updated =
                note.copy(
                    title = state.draftTitle.trim().ifBlank { note.title },
                    bodyMarkdown = state.draftBody,
                    updatedAt = Instant.now(clock),
                )
            viewModelScope.launch {
                noteRepository.update(updated)
                _uiState.update { it.copy(isEditing = false, note = updated) }
            }
        }

        private fun handleDelete() {
            viewModelScope.launch {
                noteRepository.delete(noteId)
                _uiEvents.emit(NoteDetailUiEvent.Closed)
            }
        }

        private fun handleShare() {
            val state = _uiState.value
            val note = state.note ?: return
            // Use the live draft when the user is mid-edit so the shared file matches what
            // they see on screen; otherwise the persisted note.
            val effectiveNote =
                if (state.isEditing) {
                    note.copy(
                        title = state.draftTitle,
                        bodyMarkdown = state.draftBody,
                    )
                } else {
                    note
                }
            // Single source of truth for Markdown rendering — same function the ZIP
            // export uses (see NotesViewModel.exportToZip), so a shared file and a
            // batch-exported file are byte-identical: full YAML frontmatter with
            // title, created/updated, language, tags, datetime mentions, and the
            // body. Drop into Obsidian and the metadata round-trips.
            val md = effectiveNote.toMarkdownWithFrontmatter()
            viewModelScope.launch {
                _uiEvents.emit(
                    NoteDetailUiEvent.ShareMarkdown(
                        title = effectiveNote.title.ifBlank { "Untitled" },
                        markdown = md,
                    ),
                )
            }
        }

        companion object {
            const val NOTE_ID_KEY = "noteId"
        }
    }
