package com.voicenotemd.feature.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.asr.SpeechToTextSession
import com.voicenotemd.core.asr.TranscriptChunk
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.domain.TagUsage
import com.voicenotemd.core.common.repository.GemmaModel
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.OnDeviceModelStatus
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.common.repository.WhisperModel
import com.voicenotemd.core.common.usecase.SaveNoteUseCase
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import com.voicenotemd.core.common.usecase.StructuringResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
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
        @GemmaModel private val gemmaModelRepository: OnDeviceModelRepository,
        @WhisperModel private val whisperModelRepository: OnDeviceModelRepository,
        private val recordingKeepAlive: RecordingKeepAlive,
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

        /**
         * In-flight teardown of an abandoned take ([cancelRecording] → `session.discard()`).
         * [startRecording] awaits it before collecting a new session, so a delayed teardown
         * can never release the new take's recorder or buffers (the cancel→restart race,
         * review 2026-06-10 / ADR 0027). `discard()` never transcribes, so this join is
         * bounded by the reader-thread join (~500 ms), not by whisper.
         */
        private var teardownJob: Job? = null

        /**
         * Live only while [structure] is inside its quick wait (ADR 0023): completing it
         * makes the wait return immediately with "go background" — the "Save as text now"
         * button's signal. Nulled (in a `finally`) the moment the wait is over, so a late
         * tap is a no-op.
         */
        private var skipStructuringWait: CompletableDeferred<Unit>? = null

        private var lastToggleAtMs: Long = 0L

        /**
         * Snapshot of the user's tag corpus as (tag, language) pairs, used to build the
         * EXISTING_TAGS prompt list at structure time (ADR 0012), scoped to the active
         * dictation language (ADR 0017: cross-language tag contamination was a source of
         * mixed-language output on real devices, 2026-05-22). Replaces the previous
         * whole-notes snapshot — the structuring flow only ever read the tags, so keeping
         * every note body in memory was pure overhead (review 2026-06-10 #13).
         *
         * `@Volatile` so the value written from the collector coroutine is visible to the
         * structuring coroutine when it reads. Empty list = no consistency pressure (first
         * note ever, or before the flow has emitted).
         */
        @Volatile
        private var existingTagCorpus: List<TagUsage> = emptyList()

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
            existingTagCorpus
                .let { corpus ->
                    if (forcedLanguage != null) corpus.filter { it.language == forcedLanguage } else corpus
                }
                .map { it.tag.value }
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
                noteRepository.observeTagCorpus().collect { corpus ->
                    existingTagCorpus = corpus
                }
            }
            // Watch both on-device models so the idle screen can nudge the user to import
            // whatever is missing (ADR 0022). observeStatus() is a StateFlow in production,
            // so the current status arrives immediately. Missing whisper blocks transcription;
            // missing Gemma only degrades to plain-text notes.
            viewModelScope.launch {
                whisperModelRepository.observeStatus().collect { status ->
                    _uiState.update { it.copy(whisperModelMissing = status == OnDeviceModelStatus.Missing) }
                }
            }
            viewModelScope.launch {
                gemmaModelRepository.observeStatus().collect { status ->
                    _uiState.update { it.copy(gemmaModelMissing = status == OnDeviceModelStatus.Missing) }
                }
            }
            // Keep-alive (microphone FGS) follows the phase state machine, not the UI
            // composition: capture can legitimately run while the capture screen is not
            // composed (hands-free / screen off / user browsing notes), and the service
            // must stop when capture ends regardless of what is on screen (#10,
            // review 2026-06-10). distinctUntilChanged → one start per active window.
            viewModelScope.launch {
                _uiState
                    .map { it.phase.isCaptureActive }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) recordingKeepAlive.start() else recordingKeepAlive.stop()
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
                is CaptureUiIntent.RemoveTag ->
                    updatePreview { note ->
                        note.copy(tags = note.tags.filterNot { it.value == intent.value })
                    }
                is CaptureUiIntent.AddTag -> handleAddTag(intent.value)
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
                CaptureUiIntent.SaveAsPlainText -> handleSaveAsPlainText()
            }
        }

        /**
         * "Save as text now" (ADR 0023): stop waiting for Gemma. Completing the skip
         * signal resolves the quick wait in [structure] with `null`, which routes to
         * [savePlainAndUpgradeInBackground] — the note is persisted immediately and the
         * in-flight inference becomes the background upgrade. Outside Structuring (or
         * after the wait already resolved) this is a no-op.
         */
        private fun handleSaveAsPlainText() {
            if (_uiState.value.phase != CaptureUiState.Phase.Structuring) return
            skipStructuringWait?.complete(Unit)
        }

        private fun handleSubmitText(text: String) {
            _uiState.update { it.copy(showTextInput = false, partialTranscript = text) }
            viewModelScope.launch {
                runCatching { structure(text) }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _uiState.update {
                        it.copy(
                            phase = CaptureUiState.Phase.Idle,
                            structuringStartedAtMs = null,
                            errorMessage = "Something went wrong — please try again.",
                        )
                    }
                }
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
                // Tap on the big button during the warm-up grace period: there is no real
                // PCM captured yet (the audio path is still stabilising), so transcribing
                // would just feed whisper a few hundred ms of garbage. Treat the tap as a
                // discard — back to Idle, no inference. This matches the recording-pane
                // Discard button's behaviour for symmetry.
                CaptureUiState.Phase.Preparing -> cancelRecording()
                CaptureUiState.Phase.Recording -> stopRecordingAndStructure()
                CaptureUiState.Phase.AwaitingPermission,
                CaptureUiState.Phase.Transcribing,
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
            // Enter the warm-up grace period. We capture PCM immediately (the audio path
            // starts the moment we collect speechToTextSession.start below) but the UI
            // tells the user to wait — see the kdoc on [CaptureUiState.Phase.Preparing].
            _uiState.update {
                it.copy(
                    phase = CaptureUiState.Phase.Preparing,
                    partialTranscript = "",
                    recordingDurationMs = 0L,
                    structuredPreview = null,
                    structuringFailed = false,
                )
            }
            recordingJob =
                viewModelScope.launch {
                    // Serialize against a still-running discard of the previous take: the
                    // old session must have released the AudioRecord and zeroed its buffers
                    // before we open a new one on the same SpeechToTextSession instance.
                    teardownJob?.join()
                    teardownJob = null

                    val language = _uiState.value.activeLanguage ?: Language.Unknown

                    // Collect RMS continuously while recording
                    launch {
                        speechToTextSession.rmsDb.collect { rms ->
                            _uiState.update { it.copy(rmsLevel = rms) }
                        }
                    }

                    // Captured-audio duration: drives the long-note advisory (no live
                    // transcript exists in batch mode, so duration is the only signal)
                    // and the hard duration cap below.
                    launch {
                        speechToTextSession.capturedDurationMs.collect { ms ->
                            _uiState.update { it.copy(recordingDurationMs = ms) }
                            // Hard cap (review 2026-06-10 #12): PCM accumulates in RAM at
                            // ~1.9 MB/min, and transcription temporarily needs ~4× that
                            // (chunks + concatenated copy + whisper's float image). Past
                            // 15 min the peak (~115 MB) starts flirting with the Java heap
                            // limit next to the 1.5 GB native engine — and whisper on a
                            // dictation that long is a poor experience anyway. Auto-stop
                            // and transcribe what we have instead of risking an OOM that
                            // would lose everything.
                            if (ms >= MAX_RECORDING_DURATION_MS &&
                                _uiState.value.phase == CaptureUiState.Phase.Recording
                            ) {
                                _uiState.update {
                                    it.copy(
                                        errorMessage =
                                            "Maximum recording length reached — " +
                                                "transcribing what was captured.",
                                    )
                                }
                                stopRecordingAndStructure()
                            }
                        }
                    }

                    // Transition Preparing → Recording as soon as the audio pipeline is
                    // producing usable PCM (first non-silent frame), with a safety timeout
                    // for the case where the user is completely silent after tapping the
                    // mic — without the fallback we'd be stuck in "Preparazione…" forever.
                    // Idempotent: only flips the phase if we're still in Preparing (a fast
                    // CancelRecording / ToggleRecord could have already moved us elsewhere).
                    launch {
                        // `firstOrNull` (not `first`) so that an unimplemented or stubbed
                        // `audioReady` (empty flow) does not throw NoSuchElementException —
                        // we want to fall through to the timeout in that case, not crash
                        // the recording job. The timeout itself returns `null` if the
                        // signal never arrives; both outcomes simply move us forward.
                        withTimeoutOrNull(PREPARING_TIMEOUT_MS) {
                            speechToTextSession.audioReady.firstOrNull { it }
                        }
                        _uiState.update {
                            if (it.phase == CaptureUiState.Phase.Preparing) {
                                it.copy(phase = CaptureUiState.Phase.Recording)
                            } else {
                                it
                            }
                        }
                    }

                    speechToTextSession.start(language).collect(::onTranscriptChunk)
                    // The batch session captures PCM to RAM and stays open until cancelled,
                    // so the flow normally ends via the stop button → [stopRecordingAndStructure].
                    // This branch is the safety net for the rare case where the flow completes
                    // on its own (e.g. the audio path broke): if we're still Recording we still
                    // try to structure whatever was captured rather than silently dropping it.
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
         * OS-side resources are released and the PCM buffers are zeroed in the background
         * via [SpeechToTextSession.discard] — which, unlike `stop()`, never runs the
         * transcriber on the abandoned audio (review 2026-06-10: the old `stop()` path
         * burned seconds of whisper CPU on audio we were about to throw away, and its
         * delayed buffer-zeroing could wipe a NEW take's buffers). Cancelling
         * [recordingJob] also tears down the `rmsDb` collector through structured
         * concurrency. No audio ever reached disk (ADR 0002), so this is a genuine discard.
         *
         * No-op outside [CaptureUiState.Phase.Recording] / [CaptureUiState.Phase.Preparing]
         * so a stray Cancel after stop (e.g. during Structuring) can't wipe a note that's
         * already being processed. Allowed from Preparing too — the user may tap Discard
         * (or the big stop button) before the mic warm-up completes, and that should
         * cleanly tear down the session instead of getting stuck.
         */
        private fun cancelRecording() {
            val phase = _uiState.value.phase
            if (phase != CaptureUiState.Phase.Recording && phase != CaptureUiState.Phase.Preparing) return
            recordingJob?.cancel()
            recordingJob = null
            _uiState.update {
                CaptureUiState(
                    activeLanguage = it.activeLanguage,
                    isAppending = it.isAppending,
                )
            }
            teardownJob =
                viewModelScope.launch {
                    runCatching { speechToTextSession.discard() }
                }
        }

        private fun stopRecordingAndStructure() {
            if (_uiState.value.phase != CaptureUiState.Phase.Recording) return
            // Transition: Recording → Transcribing (during whisper batch transcription) →
            // Structuring (during Gemma). [structure] flips the phase to Structuring as soon
            // as the transcript is ready; the foreground service stays alive through both
            // so the process can't be killed mid-transcription with the screen off.
            _uiState.update { it.copy(phase = CaptureUiState.Phase.Transcribing) }

            val transcript = _uiState.value.partialTranscript
            recordingJob?.cancel()
            recordingJob = null
            viewModelScope.launch {
                val finalTranscript =
                    runCatching { speechToTextSession.stop() }
                        .getOrDefault(transcript)
                        .ifBlank { transcript }
                runCatching { structure(finalTranscript) }.onFailure { e ->
                    // Re-throw CancellationException so structured concurrency is respected.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Any other unexpected exception (should not happen — StructureNoteUseCase is
                    // contractually infallible — but the safety net prevents a silent process kill).
                    _uiState.update {
                        it.copy(
                            phase = CaptureUiState.Phase.Idle,
                            structuringStartedAtMs = null,
                            errorMessage = "Something went wrong — please try again.",
                        )
                    }
                }
            }
        }

        private suspend fun structure(transcript: String) {
            val cleaned = transcript.trim()
            if (cleaned.isBlank()) {
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
            // device locale rather than leaving the model to auto-detect — see ADR 0017.
            val effectiveLanguage = forcedLanguage ?: deviceLocaleLanguage()

            // ADR 0023 middle step: Gemma runs OFF the user's critical path. We launch the
            // structuring as its own job and wait at most QUICK_STRUCTURE_WAIT_MS (or until
            // the user taps "Save as text now" — the `skip` deferred). Fast devices (warm
            // GPU) finish inside the window and keep the synchronous Reviewing flow; slow
            // ones (CPU fallback: 60-250 s budgets) fall through to an immediate plain-text
            // save and a background upgrade, so the user never stares at the spinner for
            // minutes. The async job is a child of viewModelScope, which survives in-app
            // navigation (capture is the home back-stack entry).
            val structuring: Deferred<StructuringResult> =
                viewModelScope.async {
                    // Tag corpus snapshot (ADR 0012), scoped to the working language (ADR 0017).
                    structureNote(cleaned, effectiveLanguage, tagCorpusFor(effectiveLanguage))
                }
            val skip = CompletableDeferred<Unit>()
            skipStructuringWait = skip
            val quick: StructuringResult? =
                try {
                    withTimeoutOrNull(QUICK_STRUCTURE_WAIT_MS) {
                        select<StructuringResult?> {
                            structuring.onAwait { it }
                            skip.onAwait { null }
                        }
                    }
                } finally {
                    skipStructuringWait = null
                }

            if (quick != null) {
                maybeEmitCpuAdvisory(quick)
                // Fast path — same behavior as the original synchronous flow.
                _uiState.update {
                    it.copy(
                        phase = CaptureUiState.Phase.Reviewing,
                        structuredPreview = quick.note,
                        structuringFailed = !quick.note.structured,
                        lastInferenceRaw = quick.lastRawResponse,
                        structuringStartedAtMs = null,
                    )
                }
                if (!quick.note.structured) {
                    _uiEvents.emit(CaptureUiEvent.StructuringFellBack)
                }
                return
            }

            savePlainAndUpgradeInBackground(cleaned, effectiveLanguage, structuring)
        }

        /**
         * ADR 0023 slow path: persist the transcript as a plain note RIGHT NOW (the user
         * is free immediately), keep the in-flight [structuring] job running, and when it
         * lands upgrade the note in place — but only if the user hasn't touched it in the
         * meantime (concurrent-edit rule: an edited or already-structured body wins over
         * the background result; the user's curation is never overwritten).
         *
         * Append mode is the exception: the appended note already mixes old and new
         * content, so a structured version of just the new fragment cannot be merged
         * safely. We append the plain text and drop the background result — the manual
         * "Structure with AI" action in note detail remains the upgrade path there.
         */
        private suspend fun savePlainAndUpgradeInBackground(
            transcript: String,
            language: Language,
            structuring: Deferred<StructuringResult>,
        ) {
            val now = Instant.now(clock)

            if (appendId != null) {
                val existing = noteRepository.observe(appendId).firstOrNull()
                if (existing != null) {
                    noteRepository.update(
                        existing.copy(
                            bodyMarkdown = existing.bodyMarkdown + "\n\n" + transcript,
                            updatedAt = now,
                        ),
                    )
                } else {
                    saveNote(plainNote(transcript, language, now))
                }
                structuring.cancel()
                finishPlainSave()
                return
            }

            val plain = plainNote(transcript, language, now)
            saveNote(plain)
            finishPlainSave()

            viewModelScope.launch {
                val result = runCatching { structuring.await() }.getOrNull() ?: return@launch
                maybeEmitCpuAdvisory(result)
                // The model fell back to plain text itself: nothing to upgrade with.
                if (!result.note.structured) return@launch
                val current = noteRepository.observe(plain.id).firstOrNull() ?: return@launch
                // Concurrent-edit rule: only upgrade a note that is still exactly the
                // plain transcript we saved. Deleted → observe returned null above;
                // edited or manually restructured → the user's version wins.
                if (current.structured || current.bodyMarkdown.trim() != transcript) return@launch
                noteRepository.update(
                    result.note.copy(
                        id = plain.id,
                        createdAt = current.createdAt,
                        updatedAt = Instant.now(clock),
                    ),
                )
            }
        }

        /**
         * One-time-per-process CPU advisory (ADR 0016 UX follow-up): the first time a
         * structuring result reports the CPU fallback path, tell the user why the app
         * is slower on this hardware. Process-scoped on purpose — repeating it every
         * note would be nagging, persisting it would hide a later GPU-driver fix.
         */
        private suspend fun maybeEmitCpuAdvisory(result: StructuringResult) {
            if (!result.cpuFallback || cpuAdvisoryShownThisProcess) return
            cpuAdvisoryShownThisProcess = true
            _uiEvents.emit(CaptureUiEvent.CpuFallbackAdvisory)
        }

        /** Reset to a clean Idle capture screen and tell the user the note is safe. */
        private suspend fun finishPlainSave() {
            _uiState.update {
                CaptureUiState(
                    activeLanguage = it.activeLanguage,
                    isAppending = it.isAppending,
                )
            }
            _uiEvents.emit(CaptureUiEvent.StructuringContinuesInBackground)
        }

        /** Mirror of the use case's plain-text fallback, for the immediate save (ADR 0023). */
        private fun plainNote(
            transcript: String,
            language: Language,
            now: Instant,
        ): Note {
            val firstLine =
                transcript.lineSequence()
                    .map(String::trim)
                    .firstOrNull(String::isNotEmpty)
                    .orEmpty()
            return Note(
                id = UUID.randomUUID().toString(),
                title = firstLine.take(MAX_PLAIN_TITLE_LEN).ifEmpty { "Untitled note" },
                bodyMarkdown = transcript,
                tags = emptyList(),
                mentions = emptyList(),
                language = language,
                createdAt = now,
                updatedAt = now,
                structured = false,
            )
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

        /** Normalize + dedupe before attaching: invalid or already-present tags are no-ops. */
        private fun handleAddTag(raw: String) {
            val tag = Tag.normalize(raw) ?: return
            updatePreview { note ->
                if (note.tags.any { it.value == tag.value }) note else note.copy(tags = note.tags + tag)
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
            // The phase collector above dies with viewModelScope — stop the keep-alive
            // directly so a VM death mid-capture can't leave the FGS notification up.
            runCatching { recordingKeepAlive.stop() }
            // viewModelScope is already cancelled when onCleared runs, so the discard has
            // to ride its own short-lived scope. Without this, a VM death mid-recording
            // left the full PCM un-zeroed in the heap indefinitely (ADR 0002 violation —
            // review 2026-06-10). NonCancellable: this is a privacy-critical cleanup.
            CoroutineScope(NonCancellable + Dispatchers.Default).launch {
                runCatching { speechToTextSession.discard() }
            }
            super.onCleared()
        }

        private companion object {
            /**
             * One-time-per-process latch for the CPU-fallback advisory. Companion (not
             * instance) state so a recreated ViewModel doesn't re-show it within the
             * same app run. Deliberately NOT persisted — see [maybeEmitCpuAdvisory].
             */
            @Volatile
            private var cpuAdvisoryShownThisProcess = false

            /**
             * Safety timeout for the [CaptureUiState.Phase.Preparing] → [CaptureUiState.Phase.Recording]
             * transition. On a Pixel 6a the AudioRecord warm-up completes in ~700–1000 ms; this
             * is a comfortable upper bound past which we assume the audio path is warm even if
             * no non-silent frame has been observed (e.g. the user tapped the mic and is sitting
             * silently before they start speaking). Without this fallback the UI would be stuck
             * on "Preparazione…" until the user finally made a noise.
             */
            const val PREPARING_TIMEOUT_MS = 1500L

            /**
             * Hard recording-length cap. 15 minutes of 16 kHz mono PCM ≈ 28.8 MB of
             * chunks; transcription transiently adds the concatenated ShortArray copy
             * (+28.8 MB) and whisper's normalized FloatArray (+57.6 MB) → ~115 MB peak,
             * a safe ceiling next to the LLM engine. The cap fires an auto-stop that
             * transcribes the captured audio (nothing is lost) and tells the user why.
             */
            const val MAX_RECORDING_DURATION_MS = 15 * 60 * 1000L

            /**
             * ADR 0023: how long the UI is willing to block on Gemma before saving the
             * plain note and finishing the structuring in the background. 8 s covers the
             * typical warm-GPU inference (fast devices keep the synchronous review flow)
             * while capping the worst-case spinner time on CPU-fallback devices, where
             * pass budgets legitimately reach minutes (ADR 0016).
             */
            const val QUICK_STRUCTURE_WAIT_MS = 8_000L

            /** Same cap as the use case's plain-text fallback title. */
            const val MAX_PLAIN_TITLE_LEN = 60
        }
    }
