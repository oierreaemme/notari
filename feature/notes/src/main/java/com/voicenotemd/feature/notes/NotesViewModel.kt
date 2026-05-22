package com.voicenotemd.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.markdown.toMarkdownWithFrontmatter
import com.voicenotemd.core.common.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Surfaces the persisted notes for the list screen.
 *
 * The presentation layer talks to the [NoteRepository] interface (declared in :core:common,
 * implemented by :core:database). Per ADR 0001, no `Room` types ever leak up here.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NotesViewModel
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
    ) : ViewModel() {
        /**
         * Pluggable for tests; production uses [Dispatchers.IO]. The ZIP export does file
         * encoding work that benefits from being off the main thread. Same pattern as the
         * `clock` seam in [com.voicenotemd.feature.capture.CaptureViewModel] and
         * [com.voicenotemd.feature.notedetail.NoteDetailViewModel] — Hilt does not honor
         * Kotlin default values on `@Inject` constructor parameters, so we expose a
         * mutable internal var instead.
         */
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val activeTag = MutableStateFlow<Tag?>(null)
        private val query = MutableStateFlow("")

        private val _uiState = MutableStateFlow(NotesUiState())
        val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

        private val _uiEvents = MutableSharedFlow<NotesUiEvent>(extraBufferCapacity = 4)
        val uiEvents: SharedFlow<NotesUiEvent> = _uiEvents.asSharedFlow()

        init {
            // Tag stream — independent of the active filter, so the chip row stays stable.
            viewModelScope.launch {
                noteRepository.observeAllTags().collect { tags ->
                    _uiState.update { it.copy(availableTags = tags) }
                }
            }

            // Notes stream: re-subscribed whenever the active tag flips.
            viewModelScope.launch {
                activeTag
                    .flatMapLatest { tag ->
                        if (tag == null) {
                            noteRepository.observeAll()
                        } else {
                            noteRepository.observeByTag(tag)
                        }
                    }
                    .combine(query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged()) { notes, q ->
                        notes to q
                    }
                    .collect { (notes, q) ->
                        val filtered =
                            if (q.isBlank()) {
                                notes
                            } else {
                                notes.filter { note ->
                                    note.title.contains(q, ignoreCase = true) ||
                                        note.bodyMarkdown.contains(q, ignoreCase = true)
                                }
                            }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                notes = filtered,
                                activeTag = activeTag.value,
                                // `query` is owned by the immediate-update branch in
                                // onIntent — do NOT overwrite it here or we recreate
                                // the keystroke-lag bug.
                            )
                        }
                    }
            }
        }

        fun onIntent(intent: NotesUiIntent) {
            when (intent) {
                is NotesUiIntent.UpdateQuery -> {
                    // Two writes for the same intent on purpose:
                    //  1. Mirror the typed value into `_uiState.query` IMMEDIATELY so the
                    //     OutlinedTextField in the search bar renders the user's keystroke
                    //     without lag. Before this fix, `query` was only written into
                    //     uiState in the collect block below — gated by a 200ms debounce —
                    //     so typing fast made the field appear frozen until the user paused.
                    //  2. Push into `query` MutableStateFlow which feeds the debounced
                    //     filter pipeline. Filtering still kicks in after 200ms of typing
                    //     stability; only the UI representation is now immediate.
                    _uiState.update { it.copy(query = intent.text) }
                    query.value = intent.text
                }
                is NotesUiIntent.SelectTag -> activeTag.value = intent.tag
                is NotesUiIntent.ToggleSelection ->
                    _uiState.update {
                        val current = it.selectedNoteIds
                        val newSelection =
                            if (current.contains(
                                    intent.noteId,
                                )
                            ) {
                                current - intent.noteId
                            } else {
                                current + intent.noteId
                            }
                        it.copy(selectedNoteIds = newSelection)
                    }
                NotesUiIntent.SelectAll ->
                    _uiState.update {
                        it.copy(
                            selectedNoteIds =
                                it.notes.map {
                                        note ->
                                    note.id
                                }.toSet(),
                        )
                    }
                NotesUiIntent.ClearSelection -> _uiState.update { it.copy(selectedNoteIds = emptySet()) }
                NotesUiIntent.RequestExport -> viewModelScope.launch { _uiEvents.emit(NotesUiEvent.TriggerZipPicker) }
                NotesUiIntent.RequestDeleteSelected ->
                    _uiState.update {
                        if (it.selectedNoteIds.isEmpty()) it else it.copy(showDeleteSelectedConfirm = true)
                    }
                NotesUiIntent.DismissDeleteSelected ->
                    _uiState.update {
                        it.copy(showDeleteSelectedConfirm = false)
                    }
                NotesUiIntent.ConfirmDeleteSelected -> deleteSelected()
            }
        }

        /**
         * Loop the selected ids through the repository's single-delete (cascading FKs
         * clean up tags + mentions automatically — see ADR 0001). After the deletes,
         * clear selection and dismiss the confirm dialog. Failures swallowed silently
         * for v1 — Room's `delete` only fails on programmer errors (e.g. DB closed), and
         * the destination outcome is observable: missing notes simply don't reappear.
         */
        private fun deleteSelected() {
            val ids = _uiState.value.selectedNoteIds
            if (ids.isEmpty()) {
                _uiState.update { it.copy(showDeleteSelectedConfirm = false) }
                return
            }
            viewModelScope.launch {
                ids.forEach { id -> noteRepository.delete(id) }
                _uiState.update {
                    it.copy(selectedNoteIds = emptySet(), showDeleteSelectedConfirm = false)
                }
                _uiEvents.emit(NotesUiEvent.SelectionDeleted(ids.size))
            }
        }

        fun exportToZip(openStream: () -> java.io.OutputStream?) {
            val selectedIds = _uiState.value.selectedNoteIds
            if (selectedIds.isEmpty()) return
            val allNotes = _uiState.value.notes
            val notesToExport = allNotes.filter { selectedIds.contains(it.id) }

            viewModelScope.launch(ioDispatcher) {
                try {
                    val out = openStream() ?: return@launch
                    java.util.zip.ZipOutputStream(out).use { zos ->
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        notesToExport.forEach { note ->
                            val dateStr = note.createdAt.atZone(java.time.ZoneId.systemDefault()).format(formatter)
                            val safeTitle =
                                note.title.replace(
                                    Regex("[^a-zA-Z0-9_-]"),
                                    "-",
                                ).ifBlank { "Untitled" }.take(30)
                            val filename = "${dateStr}_${safeTitle}_${note.id.take(6)}.md"

                            val entry = java.util.zip.ZipEntry(filename)
                            zos.putNextEntry(entry)

                            val content = note.toMarkdownWithFrontmatter()
                            zos.write(content.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }
                    }
                    _uiEvents.emit(
                        NotesUiEvent.ExportCompleted("Successfully exported ${notesToExport.size} notes to ZIP."),
                    )
                    _uiState.update { it.copy(selectedNoteIds = emptySet()) }
                } catch (e: Exception) {
                    _uiEvents.emit(NotesUiEvent.ExportCompleted("Failed to export: ${e.message}"))
                }
            }
        }

        // Rendering moved to `Note.toMarkdownWithFrontmatter()` in :core:common so the
        // share intent in NoteDetailViewModel produces byte-identical output.

        private companion object {
            const val SEARCH_DEBOUNCE_MS = 200L
        }
    }
