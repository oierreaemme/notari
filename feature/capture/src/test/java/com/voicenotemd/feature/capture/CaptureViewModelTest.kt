package com.voicenotemd.feature.capture

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.asr.SpeechToTextSession
import com.voicenotemd.core.asr.TranscriptChunk
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.UserSettings
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.OnDeviceModelStatus
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.common.usecase.SaveNoteUseCase
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val speechToTextSession: SpeechToTextSession = mockk(relaxed = true)
    private val structureNoteUseCase: StructureNoteUseCase = mockk(relaxed = true)
    private val saveNoteUseCase: SaveNoteUseCase = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val noteRepository: NoteRepository = mockk(relaxed = true)
    private val gemmaModelRepository: OnDeviceModelRepository = mockk(relaxed = true)
    private val whisperModelRepository: OnDeviceModelRepository = mockk(relaxed = true)

    private var currentTime = 1000L
    private val fakeClock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = Instant.ofEpochMilli(currentTime)
        }

    private lateinit var viewModel: CaptureViewModel
    private val uiEvents = mutableListOf<CaptureUiEvent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // CaptureViewModel.structure() calls `settingsRepository.observe().first()`
        // and the init block does a collect. Without this stub, a relaxed mock returns
        // an empty Flow and `.first()` throws NoSuchElementException, breaking every
        // test that exercises the structuring pipeline.
        every { settingsRepository.observe() } returns flowOf(UserSettings.Default)
        // Default audioReady stub: emit `true` immediately. The Preparing → Recording
        // transition relies on `audioReady.firstOrNull { it }` inside a withTimeoutOrNull;
        // an unstubbed mock Flow would force every test into the 1.5s timeout path, which
        // is correct but slow on the test scheduler. Tests that need to assert the
        // intermediate Preparing state can override this with a MutableStateFlow.
        every { speechToTextSession.audioReady } returns flowOf(true)
        // Default: both models present, so the setup-needed banner never interferes with
        // the existing recording/structuring tests. Tests asserting the banner override these.
        every { gemmaModelRepository.observeStatus() } returns flowOf(OnDeviceModelStatus.Present)
        every { whisperModelRepository.observeStatus() } returns flowOf(OnDeviceModelStatus.Present)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(appendId: String? = null): CaptureViewModel {
        val map = mutableMapOf<String, Any?>()
        if (appendId != null) map["appendId"] = appendId
        val savedStateHandle = SavedStateHandle(map)

        return CaptureViewModel(
            speechToTextSession,
            structureNoteUseCase,
            saveNoteUseCase,
            settingsRepository,
            noteRepository,
            gemmaModelRepository,
            whisperModelRepository,
            savedStateHandle,
        ).also { vm ->
            vm.clock = fakeClock
            // Collect events into a list. Launch on the testDispatcher scope so the
            // collector lives for the whole test method.
            CoroutineScope(testDispatcher).launch {
                vm.uiEvents.collect { uiEvents.add(it) }
            }
        }
    }

    @Test
    fun `setupNeeded reflects missing whisper model`() =
        runTest {
            every { whisperModelRepository.observeStatus() } returns
                flowOf(OnDeviceModelStatus.Missing)
            every { gemmaModelRepository.observeStatus() } returns
                flowOf(OnDeviceModelStatus.Present)

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.uiState.value.whisperModelMissing).isTrue()
            assertThat(viewModel.uiState.value.gemmaModelMissing).isFalse()
            assertThat(viewModel.uiState.value.setupNeeded).isTrue()
        }

    @Test
    fun `setupNeeded is false when both models present`() =
        runTest {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.uiState.value.setupNeeded).isFalse()
        }

    @Test
    fun `debounce ToggleRecord ignores taps under 300ms`() =
        runTest {
            viewModel = createViewModel()

            // Prima chiamata (t=1000)
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verifica transizione
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.AwaitingPermission)
            assertThat(uiEvents.last()).isEqualTo(CaptureUiEvent.RequestPermission)

            val initialEventsCount = uiEvents.size

            // Seconda chiamata immediata (t=1100), dovrebbe essere ignorata dal debounce
            currentTime += 100
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            testDispatcher.scheduler.advanceUntilIdle()

            // Gli eventi non dovrebbero essere cresciuti perché l'intent è droppato
            assertThat(uiEvents.size).isEqualTo(initialEventsCount)

            // Terza chiamata ben distanziata (t=1500).
            // Supererà il debounce, MA cadrà nell'ignore per la phase AwaitingPermission,
            // e questo dimostra che ha passato il check del debounce ma non ha alterato nulla
            currentTime += 400
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(uiEvents.size).isEqualTo(initialEventsCount)
        }

    @Test
    fun `quando phase e gia Structuring, una chiamata a ToggleRecord viene ignorata`() =
        runTest {
            // Hold the structuring use case in a suspended state so phase stays at Structuring
            // long enough for the test's assertions to run. Without this, the relaxed mock
            // returns immediately and the VM would advance to Reviewing before we can check.
            coEvery { structureNoteUseCase.invoke(any(), any(), any()) } coAnswers { awaitCancellation() }

            viewModel = createViewModel()
            // Portiamo a Structuring forzatamente via un hack per il test: text input
            viewModel.onIntent(CaptureUiIntent.ToggleTextInput)
            viewModel.onIntent(CaptureUiIntent.SubmitText("Test transcript"))
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Structuring)

            // Ora proviamo a fare tap su record
            currentTime += 5000 // Ben oltre il debounce
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            testDispatcher.scheduler.advanceUntilIdle()

            // La fase deve restare Structuring e non deve essere diventata Idle o AwaitingPermission
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Structuring)
        }

    @Test
    fun `append refresh updatedAt`() =
        runTest {
            val existingNoteId = "note-uuid"
            val existingNote =
                Note(
                    id = existingNoteId,
                    title = "Titolo",
                    bodyMarkdown = "Corpo vecchio",
                    tags = emptyList(),
                    mentions = emptyList(),
                    language = Language.Italian,
                    createdAt = Instant.ofEpochMilli(0L),
                    updatedAt = Instant.ofEpochMilli(0L),
                    structured = true,
                )

            every { noteRepository.observe(existingNoteId) } returns flowOf(existingNote)

            viewModel = createViewModel(appendId = existingNoteId)
            testDispatcher.scheduler.advanceUntilIdle()

            // Prepariamo la vista con una nota strutturata preview
            val newNoteChunk =
                Note(
                    id = "temp-id",
                    title = "Nuovo",
                    bodyMarkdown = "Corpo nuovo",
                    tags = emptyList(),
                    mentions = emptyList(),
                    language = Language.Italian,
                    createdAt = Instant.ofEpochMilli(currentTime),
                    updatedAt = Instant.ofEpochMilli(currentTime),
                    structured = true,
                )

            val fakeResult =
                com.voicenotemd.core.common.usecase.StructuringResult(
                    note = newNoteChunk,
                    lastRawResponse = null,
                )
            // Set up the mock to return the new chunk
            io.mockk.coEvery { structureNoteUseCase.invoke(any(), any(), any()) } returns fakeResult

            // Trigger structure flow via text input
            viewModel.onIntent(CaptureUiIntent.ToggleTextInput)
            viewModel.onIntent(CaptureUiIntent.SubmitText("Corpo nuovo"))
            testDispatcher.scheduler.advanceUntilIdle()

            // Avanziamo il tempo
            currentTime = 5000L

            viewModel.onIntent(CaptureUiIntent.Save)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verifichiamo che update sia stato chiamato con l'updatedAt rinfrescato.
            // noteRepository.update is a suspend fun, so we use coVerify instead of verify.
            val updatedNoteSlot = slot<Note>()
            coVerify { noteRepository.update(capture(updatedNoteSlot)) }

            assertThat(updatedNoteSlot.captured.updatedAt).isEqualTo(Instant.ofEpochMilli(5000L))
            assertThat(updatedNoteSlot.captured.bodyMarkdown).isEqualTo("Corpo vecchio\n\nCorpo nuovo")
        }

    @Test
    fun `discard preserva isAppending`() =
        runTest {
            viewModel = createViewModel(appendId = "existing-note-id")
            testDispatcher.scheduler.advanceUntilIdle()

            // Assicuriamo che isAppending sia true all'inizio
            assertThat(viewModel.uiState.value.isAppending).isTrue()

            val fakeResult =
                com.voicenotemd.core.common.usecase.StructuringResult(
                    note =
                        Note(
                            id = "temp-id",
                            title = "Nuovo",
                            bodyMarkdown = "Corpo nuovo",
                            tags = emptyList(),
                            mentions = emptyList(),
                            language = Language.Italian,
                            createdAt = Instant.ofEpochMilli(currentTime),
                            updatedAt = Instant.ofEpochMilli(currentTime),
                            structured = true,
                        ),
                    lastRawResponse = null,
                )
            io.mockk.coEvery { structureNoteUseCase.invoke(any(), any(), any()) } returns fakeResult
            viewModel.onIntent(CaptureUiIntent.ToggleTextInput)
            viewModel.onIntent(CaptureUiIntent.SubmitText("Corpo nuovo"))
            testDispatcher.scheduler.advanceUntilIdle()

            // Discard
            viewModel.onIntent(CaptureUiIntent.DiscardPreview)
            testDispatcher.scheduler.advanceUntilIdle()

            // isAppending DEVE ancora essere true
            assertThat(viewModel.uiState.value.isAppending).isTrue()
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Idle) // because of reset
        }

    @Test
    fun `cancel recording discards everything and returns to Idle without structuring`() =
        runTest {
            // Keep the recognizer flow open so the VM stays in Recording until we cancel.
            every { speechToTextSession.rmsDb } returns emptyFlow()
            every { speechToTextSession.start(any()) } returns
                flow {
                    emit(
                        TranscriptChunk(
                            "questa nota è sbagliata",
                            isFinal = false,
                            detectedLanguage = Language.Italian,
                        ),
                    )
                    awaitCancellation()
                }

            viewModel = createViewModel()
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = true))
            testDispatcher.scheduler.advanceUntilIdle()
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Recording)

            // Abandon the recording.
            currentTime += 1000
            viewModel.onIntent(CaptureUiIntent.CancelRecording)
            testDispatcher.scheduler.advanceUntilIdle()

            // Back to a clean Idle screen, nothing carried over.
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Idle)
            assertThat(viewModel.uiState.value.partialTranscript).isEmpty()
            assertThat(viewModel.uiState.value.structuredPreview).isNull()
            assertThat(viewModel.uiState.value.rmsLevel).isEqualTo(0f)
            // The note was abandoned: structuring must never run.
            coVerify(exactly = 0) { structureNoteUseCase.invoke(any(), any(), any()) }
        }

    @Test
    fun `start recording lands in Preparing first and only moves to Recording once audio is ready`() =
        runTest {
            // Drive audioReady manually so we can observe the intermediate Preparing state.
            val audioReady = MutableStateFlow(false)
            every { speechToTextSession.audioReady } returns audioReady.asStateFlow()
            every { speechToTextSession.rmsDb } returns emptyFlow()
            every { speechToTextSession.start(any()) } returns
                flow {
                    emit(TranscriptChunk("", isFinal = false))
                    awaitCancellation()
                }

            viewModel = createViewModel()
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = true))
            // Let the recording job spin up but DO NOT advance through the warm-up timeout
            // yet. `runCurrent` runs only the currently-queued tasks (no virtual-time skip),
            // so we see the state immediately after startRecording() but before the warm-up
            // delay would expire — i.e. the Preparing snapshot we care about.
            testDispatcher.scheduler.runCurrent()

            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Preparing)

            // Mic warm-up done: audioReady flips to true. The collector inside the VM
            // should now see this and move us to Recording without waiting for the
            // safety timeout.
            audioReady.value = true
            testDispatcher.scheduler.runCurrent()

            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Recording)
        }

    @Test
    fun `cancel during preparing returns to Idle without structuring`() =
        runTest {
            // Keep audioReady held at false so the VM stays in Preparing for the test.
            val audioReady = MutableStateFlow(false)
            every { speechToTextSession.audioReady } returns audioReady.asStateFlow()
            every { speechToTextSession.rmsDb } returns emptyFlow()
            every { speechToTextSession.start(any()) } returns
                flow {
                    emit(TranscriptChunk("", isFinal = false))
                    awaitCancellation()
                }

            viewModel = createViewModel()
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = true))
            testDispatcher.scheduler.runCurrent()
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Preparing)

            // User decides to abort during the warm-up grace period.
            currentTime += 200
            viewModel.onIntent(CaptureUiIntent.CancelRecording)
            testDispatcher.scheduler.advanceUntilIdle()

            // Back to a clean Idle screen, nothing structured.
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Idle)
            coVerify(exactly = 0) { structureNoteUseCase.invoke(any(), any(), any()) }
        }

    @Test
    fun `cancel recording preserves isAppending and active language`() =
        runTest {
            every { speechToTextSession.rmsDb } returns emptyFlow()
            every { speechToTextSession.start(any()) } returns
                flow {
                    emit(TranscriptChunk("bozza", isFinal = false))
                    awaitCancellation()
                }

            viewModel = createViewModel(appendId = "existing-note-id")
            viewModel.onIntent(CaptureUiIntent.ToggleRecord)
            viewModel.onIntent(CaptureUiIntent.PermissionResult(granted = true))
            testDispatcher.scheduler.advanceUntilIdle()
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Recording)

            currentTime += 1000
            viewModel.onIntent(CaptureUiIntent.CancelRecording)
            testDispatcher.scheduler.advanceUntilIdle()

            // Cancelling a recording must not drop the append context — the user is still
            // appending to the same note, they just abandoned this take.
            assertThat(viewModel.uiState.value.isAppending).isTrue()
            assertThat(viewModel.uiState.value.phase).isEqualTo(CaptureUiState.Phase.Idle)
        }
}
