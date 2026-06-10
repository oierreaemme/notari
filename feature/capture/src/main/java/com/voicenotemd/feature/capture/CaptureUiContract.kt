package com.voicenotemd.feature.capture

import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note

/**
 * The single MVI surface for the capture screen.
 *
 * Per ADR 0006 every feature ViewModel exposes:
 * - a `StateFlow<XxxUiState>` for the screen state (this file)
 * - a `SharedFlow<XxxUiEvent>` for one-shot effects (this file)
 * - a `fun onIntent(intent: XxxUiIntent)` for user actions (this file)
 *
 * No variations. Anything navigation-related goes through [CaptureUiEvent], anything
 * the user can trigger goes through [CaptureUiIntent].
 */
data class CaptureUiState(
    val phase: Phase = Phase.Idle,
    val activeLanguage: Language? = null,
    val partialTranscript: String = "",
    val rmsLevel: Float = 0f,
    /**
     * Milliseconds of audio captured in the current take. With batch ASR (whisper, ADR
     * 0018) there is no live transcript, so this is what drives the long-note advisory
     * on the recording pane — the old transcript-length threshold never fired in batch
     * mode (review 2026-06-10).
     */
    val recordingDurationMs: Long = 0L,
    val isAppending: Boolean = false,
    val structuredPreview: Note? = null,
    val structuringFailed: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Last raw response we got back from the inference engine. Surfaced in the review
     * pane only when [structuringFailed] is true, so the developer (or curious user) can
     * see exactly what came out of Gemma instead of guessing why JSON parsing failed.
     * `null` until we have something to show.
     */
    val lastInferenceRaw: String? = null,
    /** When true, the language picker bottom sheet is visible. */
    val showLanguagePicker: Boolean = false,
    /** When true, the raw text input dialog/sheet is visible. */
    val showTextInput: Boolean = false,
    /**
     * Epoch-millis at which we entered the [Phase.Structuring] state, used by the
     * structuring pane to render an elapsed-time counter. `null` when not currently
     * structuring. Set whenever phase transitions to Structuring; cleared when we
     * leave that phase. Lets the UI show "12s elapsed of ~25s estimated" instead of
     * an opaque spinner — important because on-device structuring is 15-60s on
     * mid-tier Android (see ADR 0009).
     */
    val structuringStartedAtMs: Long? = null,
    /**
     * Whether each on-device model is absent, driving the "setup needed" banner on the
     * idle capture screen (ADR 0022). Whisper missing means dictation can't be transcribed
     * at all; Gemma missing only degrades to plain-text notes (the capture flow still
     * works, per ADR 0005). Default `false` so the banner never flashes before the model
     * repositories have reported their real status.
     */
    val whisperModelMissing: Boolean = false,
    val gemmaModelMissing: Boolean = false,
) {
    /** True when at least one model is missing — the idle screen should nudge to Settings. */
    val setupNeeded: Boolean get() = whisperModelMissing || gemmaModelMissing

    /**
     * Coarse-grained phase of the capture flow. This drives the visual state machine on
     * the screen — the sub-state lives in the other [CaptureUiState] fields.
     */
    enum class Phase {
        /** Nothing is happening. The big record button is shown. */
        Idle,

        /** The mic permission flow is in progress. We don't render anything dramatic. */
        AwaitingPermission,

        /**
         * Mic permission was granted and [com.voicenotemd.core.asr.SpeechToTextSession.start]
         * was just called, but the audio path hasn't yet produced its first usable frame —
         * AudioRecord's AGC + DSP take ~700–1000 ms to stabilise on a Pixel 6a. The UI
         * shows a brief "Preparazione…" indicator so the user doesn't speak into the
         * warm-up window and lose the first words of the dictation. Transitions to
         * [Recording] on the first non-silent PCM frame OR after a safety timeout.
         */
        Preparing,

        /** SpeechRecognizer is listening; the partial transcript may be growing. */
        Recording,

        /**
         * Recording stopped; the batch ASR engine (whisper.cpp) is turning the captured PCM
         * into text. Distinct from [Structuring] so the UI can honestly show the two steps
         * — whisper transcribe, then Gemma structure — instead of one long "Structuring…" wait.
         */
        Transcribing,

        /** Recording stopped, Gemma is converting transcript → structured note. */
        Structuring,

        /** A structured (or fallback) note is shown for review/edit before save. */
        Reviewing,

        /** Save just happened. The next event will navigate away. */
        Saved,
        ;

        /**
         * True while the audio pipeline is doing work that must survive screen-off /
         * backgrounding: capturing PCM (Preparing/Recording) or running whisper on it
         * (Transcribing). Drives the microphone foreground service keep-alive — owned
         * by the ViewModel, not the composition (review 2026-06-10 #10).
         */
        val isCaptureActive: Boolean
            get() = this == Preparing || this == Recording || this == Transcribing
    }
}

sealed interface CaptureUiIntent {
    /** User tapped the record button while idle. Triggers permission check + start. */
    data object ToggleRecord : CaptureUiIntent

    /**
     * User abandoned the in-progress recording. The audio buffer and partial transcript
     * are discarded — nothing is transcribed, structured, or saved — and the screen
     * returns to [CaptureUiState.Phase.Idle]. No audio ever reached disk (ADR 0002), so
     * this is a true discard, not a soft delete.
     */
    data object CancelRecording : CaptureUiIntent

    /** Permission system returned a result. Drives the next step. */
    data class PermissionResult(val granted: Boolean) : CaptureUiIntent

    /** User edited the title in the review preview. */
    data class EditTitle(val title: String) : CaptureUiIntent

    /** User edited the body in the review preview. */
    data class EditBody(val body: String) : CaptureUiIntent

    /** User removed a tag chip in the review preview. [value] is the tag's normalized value. */
    data class RemoveTag(val value: String) : CaptureUiIntent

    /**
     * User typed a new tag in the review preview. The raw [value] goes through
     * `Tag.normalize` in the ViewModel — invalid or duplicate values are dropped silently.
     */
    data class AddTag(val value: String) : CaptureUiIntent

    /** User pressed Save on the review preview. */
    data object Save : CaptureUiIntent

    /** User cancelled the review preview without saving. The note is discarded. */
    data object DiscardPreview : CaptureUiIntent

    /** A transient error has been acknowledged by the user. */
    data object DismissError : CaptureUiIntent

    /** Show the language picker sheet. */
    data object OpenLanguagePicker : CaptureUiIntent

    /** Hide the language picker sheet without changing the current selection. */
    data object DismissLanguagePicker : CaptureUiIntent

    /**
     * Pin (or unpin, with `null`) the dictation language. Persists through
     * [SettingsRepository], so the choice carries over to the next launch and to
     * Settings.
     */
    data class PickLanguage(val language: Language?) : CaptureUiIntent

    /** Show/Hide the text input dialog for raw manual entry. */
    data object ToggleTextInput : CaptureUiIntent

    /** Submits raw text to be structured by Gemma. */
    data class SubmitText(val text: String) : CaptureUiIntent

    /**
     * User opted out of waiting for Gemma (ADR 0023): save the transcript as a plain
     * note right now and let structuring finish in the background, upgrading the note
     * in place when it lands. Only meaningful during [CaptureUiState.Phase.Structuring].
     */
    data object SaveAsPlainText : CaptureUiIntent
}

sealed interface CaptureUiEvent {
    /** Caller-side: navigate to the freshly persisted note. */
    data class NavigateToNote(val noteId: String) : CaptureUiEvent

    /** The OS needs to be asked for the RECORD_AUDIO permission. */
    data object RequestPermission : CaptureUiEvent

    /** A structuring failure was salvaged with the plain-text fallback. Inform the user. */
    data object StructuringFellBack : CaptureUiEvent

    /**
     * The note was saved as plain text and Gemma keeps structuring in the background
     * (ADR 0023: quick-wait expired or the user tapped "Save as text now"). The note
     * will upgrade in place when structuring lands; inform the user unobtrusively.
     */
    data object StructuringContinuesInBackground : CaptureUiEvent

    /**
     * First structuring of this process ran on the CPU fallback path: show the one-time
     * "this phone runs Gemma on CPU" advisory (ADR 0016 UX follow-up) so the user knows
     * why structuring is slower on their hardware.
     */
    data object CpuFallbackAdvisory : CaptureUiEvent
}
