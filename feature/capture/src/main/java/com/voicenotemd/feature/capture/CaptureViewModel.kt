package com.voicenotemd.feature.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.asr.SpeechToTextSession
import com.voicenotemd.core.asr.TranscriptChunk
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.common.usecase.SaveNoteUseCase
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the recording → structuring → review → save flow.
 *
 * State machine boundaries:
 * - Permission live entirely outside the VM (the screen owns the launcher), but the VM
 *   is the source of truth for *whether we are waiting on a permission decision*.
 * - The recording session is a child coroutine of [viewModelScope]; cancelling it
 *   triggers [SpeechToTextSession]'s `awaitClose`, which destroys the recognizer and
 *   releases the OS-side audio resources (no audio file ever gets written — see ADR 0002).
 * - Structuring is a single suspending call to [StructureNoteUseCase] which is contractually
 *   infallible (it returns a plain-text fallback if the model can't produce JSON).
 */
@HiltViewModel
class CaptureViewModel
    @Inject
    constructor(
        private val speechToTextSession: SpeechToTextSession,
        private val structureNote: StructureNoteUseCase,
        private val saveNote: SaveNoteUseCase,
        private val settingsRepository: SettingsRepository,
        private val noteRepository: NoteRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        /**
         * Pluggable for tests; production uses [Clock.systemUTC]. Same pattern as
         * [com.voicenotemd.feature.notedetail.NoteDetailViewModel]. We avoid passing this
         * through the constructor because Hilt does not honor Kotlin default values for
         * `@Inject`-annotated constructor parameters.
         */
        internal var clock: Clock = Clock.systemUTC()

        private val appendId: String? = savedStateHandle["appendId"]

        private val _uiState = MutableStateFlow(CaptureUiState(isAppending = appendId != null))
        val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

        private val _uiEvents = MutableSharedFlow<CaptureUiEvent>(extraBufferCapacity = 4)
        val uiEvents: SharedFlow<CaptureUiEvent> = _uiEvents.asSharedFlow()

        /** Live recording job. `null` whenever we're not recording. */
        private var recordingJob: Job? = null

        private var lastToggleAtMs: Long = 0L

        /**
         * Snapshot of the user's notes, used to derive the tag corpus passed to Gemma at
         * structure time (see ADR 0012). We keep whole notes — not just a flat tag list —
         * so we can scope the corpus to the active dictation language and avoid feeding
         * Italian tags into an English note (ADR 0017: cross-language tag contamination
         * was a source of mixed-language output on real devices, 2026-05-22).
         *
         * `@Volatile` so the value written from the collector coroutine is visible to the
         * structuring coroutine when it reads. Empty list = no consistency pressure (first
         * note ever, or before the flow has emitted).
         */
        @Volatile
        private var existingNotes: List<Note> = emptyList()

        /**
         * Tag corpus to pass to Gemma for a note in [forcedLanguage].
         *
         * When a language is pinned we restrict the corpus to tags from notes in THAT
         * language, so the model is never nudged to reuse a tag in the wrong language
         * (which then bleeds into the title/body via autoregressive generation). When no
         * language is pinned (auto-detect) we pass the whole corpus unchanged — the prompt's
         * own "tags in the note's own language" rule is the guard in that case.
         */
        private fun tagCorpusFor(forcedLanguage: Language?): List<String> =
            existingNotes
                .let { notes ->
                    if (forcedLanguage != null) notes.filter { it.language == forcedLanguage } else notes
                }
                .flatMap { it.tags }
                .map { it.value }
                .distinct()

        /**
         * The device-locale language, used as the working language when the user has not
         * pinned one ("Auto"). Falls back to English when the device locale isn't one of the
         * supported languages, so the prompt always gets a concrete steer.
         */
        private fun deviceLocaleLanguage(): Language =
            Language.fromBcp47(Locale.getDefault().language)
                .takeIf { it != Language.Unknown } ?: Language.English

        init {
            // Surface the user's language preference so the UI can show the active locale chip.
            viewModelScope.launch {
                settingsRepository.observe().collect { settings ->
                    _uiState.update { it.copy(activeLanguage = settings.forcedLanguage) }
                }
            }
            // Keep a live snapshot of the user's tag corpus so the next structuring call
            // can pass it to Gemma. Room's Flow emits on every change, so deletes and
            // edits are reflected without us reloading manually.
            viewModelScope.launch {
                noteRepository.observeAll().collect { notes ->
                    existingNotes = notes
                }
            }
            // Fire-and-forget engine warm-up. The user typically takes a few seconds to
            // glance at the screen and tap mic; we use that window to load the ~1.5 GB
            // engine in the background so the first `generate()` call hits a warm path.
            // Without this, the first dictation in a fresh process pays the full cold-start
            // budget (15-30s on Pixel 6a, see ADR 0009) and risks timing out on long notes.
            viewModelScope.launch {
                runCatching { structureNote.warmUp() }
            }
        }

        /**
         * Idempotent re-arm of the Gemma engine. Called from the route on `ON_RESUME` so
         * that if `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)` released the 1.5 GB
         * allocation while the user was elsewhere, we start reloading the moment they
         * land back on capture — instead of paying the full cold-load inside the first
         * Pass 1 budget (the dominant cause of timeout-triggered plain-text fallbacks
         * in real-device usage, fix dated 2026-05-16).
         *
         * `runCatching` keeps this fire-and-forget on devices where the engine cannot
         * be loaded at all (e.g. user hasn't imported the .litertlm yet) — we never want
         * a warm-up failure to surface as a UI error.
         */
        fun warmUpIfNeeded() {
            viewModelScope.launch {
                runCatching { structureNote.warmUp() }
            }
        }

        fun onIntent(intent: CaptureUiIntent) {
            when (intent) {
                CaptureUiIntent.ToggleRecord -> handleToggleRecord()
                CaptureUiIntent.CancelRecording -> cancelRecording()
                is CaptureUiIntent.PermissionResult -> handlePermissionResult(intent.granted)
                is CaptureUiIntent.EditTitle -> updatePreview { it.copy(title = intent.title) }
                is CaptureUiIntent.EditBody -> updatePreview { it.copy(bodyMarkdown = intent.body) }
                CaptureUiIntent.Save -> handleSave()
                CaptureUiIntent.DiscardPreview -> handleDiscard()
                CaptureUiIntent.DismissError ->
                    _uiState.update { it.copy(errorMessage = null) }
                CaptureUiIntent.OpenLanguagePicker ->
                    _uiState.update { it.copy(showLanguagePicker = true) }
                CaptureUiIntent.DismissLanguagePicker ->
                    _uiState.update { it.copy(showLanguagePicker = false) }
                is CaptureUiIntent.PickLanguage -> handlePickLanguage(intent.language)
                CaptureUiIntent.ToggleTextInput ->
                    _uiState.update { it.copy(showTextInput = !it.showTextInput) }
                is CaptureUiIntent.SubmitText -> handleSubmitText(intent.text)
            }
        }

        private fun handleSubmitText(text: String) {
            _uiState.update { it.copy(showTextInput = false, partialTranscript = text) }
            viewModelScope.launch {
                structure(text)
            }
        }

        private fun handlePickLanguage(language: Language?) {
            viewModelScope.launch {
                settingsRepository.setForcedLanguage(language?.takeIf { it != Language.Unknown })
                _uiState.update { it.copy(showLanguagePicker = false) }
            }
        }

        private fun handleToggleRecord() {
            val now = clock.millis()
            if (now - lastToggleAtMs < 300L) return
            lastToggleAtMs = now

            when (_uiState.value.phase) {
                CaptureUiState.Phase.Idle, CaptureUiState.Phase.Saved -> {
                    // We don't have a way to inspect the permission grant from inside the VM,
                    // so we always emit a request and let the screen decide whether the OS
                    // needs to ask. The screen short-circuits with `granted = true` if the
                    // permission is already held.
                    _uiState.update {
                        it.copy(phase = CaptureUiState.Phase.AwaitingPermission, errorMessage = null)
                    }
                    viewModelScope.launch { _uiEvents.emit(CaptureUiEvent.RequestPermission) }
                }
                CaptureUiState.Phase.Recording -> stopRecordingAndStructure()
                CaptureUiState.Phase.AwaitingPermission,
                CaptureUiState.Phase.Structuring,
                CaptureUiState.Phase.Reviewing,
                -> Unit // ignore double-taps in those phases
            }
        }

        private fun handlePermissionResult(granted: Boolean) {
            if (!granted) {
                _uiState.update {
                    it.copy(
                        phase = CaptureUiState.Phase.Idle,
                        errorMessage = "Microphone access is needed to capture a note.",
                    )
                }
                return
            }
            startRecording()
        }

        private fun startRecording() {
            recordingJob?.cancel()
            _uiState.update {
                it.copy(
                    phase = CaptureUiState.Phase.Recording,
                    partialTranscript = "",
                    structuredPreview = null,
                    structuringFailed = false,
                )
            }
            recordingJob =
                viewModelScope.launch {
                    val language = _uiState.value.activeLanguage ?: Language.Unknown

                    // Collect RMS continuously while recording
                    launch {
                        speechToTextSession.rmsDb.collect { rms ->
                            _uiState.update { it.copy(rmsLevel = rms) }
                        }
                    }

                    speechToTextSession.start(language).collect(::onTranscriptChunk)
                    // The flow only completes on a fatal recognizer error (mic unavailable,
                    // audio path broken). Pauses, end-of-utterance, ERROR_NO_MATCH and
                    // ERROR_SPEECH_TIMEOUT are absorbed by the continuous-listen loop inside
                    // AndroidSpeechToTextSession so the user can dictate long-form with
                    // natural pauses. Normal termination happens via the stop button →
                    // [stopRecordingAndStructure]; this branch only fires on the rare
                    // fatal case, in which case we still try to structure whatever the
                    // recognizer managed to capture.
                    if (_uiState.value.phase == CaptureUiState.Phase.Recording) {
                        stopRecordingAndStructure()
                    }
                }
        }

        private fun onTranscriptChunk(chunk: TranscriptChunk) {
            _uiState.update { it.copy(partialTranscript = chunk.text) }
        }

        /**
         * Abandon the in-progress recording without structuring or saving anything.
         *
         * The UI is reset to Idle synchronously for instant feedback; the recognizer's
         * OS-side resources are released in the background via [SpeechToTextSession.stop],
         * whose returned transcript we deliberately discard. Cancelling [recordingJob]
         * also tears down the `rmsDb` collector through structured concurrency. No audio
         * ever reached disk (ADR 0002), so this is a genuine discard.
         *
         * No-op outside [CaptureUiState.Phase.Recording] so a stray Cancel after stop
         * (e.g. during Structuring) can't wipe a note that's already being processed.
         */
        private fun cancelRecording() {
            if (_uiState.value.phase != CaptureUiState.Phase.Recording) return
            recordingJob?.cancel()
            recordingJob = null
            _uiState.update {
                CaptureUiState(
                    activeLanguage = it.activeLanguage,
                    isAppending = it.isAppending,
                )
            }
            viewModelScope.launch {
                runCatching { speechToTextSession.stop() }
            }
        }

        private fun stopRecordingAndStructure() {
            if (_uiState.value.phase != CaptureUiState.Phase.Recording) return
            _uiState.update {
                it.copy(
                    phase = CaptureUiState.Phase.Structuring,
                    structuringStartedAtMs = clock.millis(),
                )
            }

            val transcript = _uiState.value.partialTranscript
            recordingJob?.cancel()
            recordingJob = null
            viewModelScope.launch {
                val finalTranscript =
                    runCatching { speechToTextSession.stop() }
                        .getOrDefault(transcript)
                        .ifBlank { transcript }
                structure(finalTranscript)
            }
        }

        private suspend fun structure(transcript: String) {
            if (transcript.isBlank()) {
                _uiState.update {
                    it.copy(
                        phase = CaptureUiState.Phase.Idle,
                        partialTranscript = "",
                        errorMessage = "Nothing was captured — try again.",
                        structuringStartedAtMs = null,
                    )
                }
                return
            }
            _uiState.update {
                it.copy(
                    phase = CaptureUiState.Phase.Structuring,
                    // Set the timer here too in case we arrived via text-input (where
                    // stopRecordingAndStructure did not run). Idempotent if already set.
                    structuringStartedAtMs = it.structuringStartedAtMs ?: clock.millis(),
                )
            }

            val forcedLanguage = settingsRepository.observe().first().forcedLanguage
            // Resolve the working language. In "Auto" (no explicit pin) we fall back to the
            // device locale rather than leaving the model to auto-detect: Android's
            // SpeechRecognizer has no real language auto-detection, so it already transcribes
            // in the device language. Steering the structuring prompt to that same language
            // keeps title/tags/body consistent instead of the mixed-language output we saw on
            // short notes (real device 2026-05-22). An explicit pick from the selector always
            // overrides this. See ADR 0017.
            val effectiveLanguage = forcedLanguage ?: deviceLocaleLanguage()
            // Pass the current tag corpus snapshot so Gemma is nudged to reuse an existing tag
            // rather than coin a synonymous new one (ADR 0012), scoped to the working language
            // so we don't feed cross-language tags (ADR 0017).
            val result = structureNote(transcript, effectiveLanguage, tagCorpusFor(effectiveLanguage))
            val note = result.note

            _uiState.update {
                it.copy(
                    phase = CaptureUiState.Phase.Reviewing,
                    structuredPreview = note,
                    structuringFailed = !note.structured,
                    lastInferenceRaw = result.lastRawResponse,
                    structuringStartedAtMs = null,
                )
            }
            if (!note.structured) {
                _uiEvents.emit(CaptureUiEvent.StructuringFellBack)
            }
        }

        private fun handleSave() {
            val note = _uiState.value.structuredPreview ?: return
            viewModelScope.launch {
                val finalNote =
                    if (appendId != null) {
                        val existingNote = noteRepository.observe(appendId).first()
                        if (existingNote != null) {
                            existingNote.copy(
                                bodyMarkdown = existingNote.bodyMarkdown + "\n\n" + note.bodyMarkdown,
                                tags = (existingNote.tags + note.tags).distinct(),
                                updatedAt = Instant.now(clock),
                            ).also { noteRepository.update(it) }
                        } else {
                            note.also { saveNote(it) }
                        }
                    } else {
                        saveNote(note)
                        note
                    }

                _uiState.update {
                    it.copy(
                        phase = CaptureUiState.Phase.Saved,
                        partialTranscript = "",
                        structuredPreview = null,
                        structuringStartedAtMs = null,
                    )
                }
                _uiEvents.emit(CaptureUiEvent.NavigateToNote(finalNote.id))
            }
        }

        private fun handleDiscard() {
            _uiState.update {
                CaptureUiState(
                    activeLanguage = it.activeLanguage,
                    isAppending = it.isAppending,
                )
            }
        }

        private inline fun updatePreview(
            transform: (com.voicenotemd.core.common.domain.Note) -> com.voicenotemd.core.common.domain.Note,
        ) {
            _uiState.update { state ->
                val current = state.structuredPreview ?: return@update state
                state.copy(structuredPreview = transform(current))
            }
        }

        override fun onCleared() {
            recordingJob?.cancel()
            super.onCleared()
        }
    }
