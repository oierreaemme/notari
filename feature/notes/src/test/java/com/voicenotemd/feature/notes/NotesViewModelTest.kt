package com.voicenotemd.feature.notes

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.repository.NoteRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val noteRepository: NoteRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ZIP filename univoco per Untitled duplicati`() =
        runTest {
            val note1 =
                Note(
                    id = "id-12345678",
                    // diverrà Untitled
                    title = "",
                    bodyMarkdown = "Corpo 1",
                    tags = emptyList(),
                    mentions = emptyList(),
                    language = Language.English,
                    createdAt = Instant.ofEpochMilli(1700000000000L),
                    updatedAt = Instant.ofEpochMilli(1700000000000L),
                    structured = true,
                )
            val note2 =
                Note(
                    id = "id-87654321",
                    // diverrà Untitled
                    title = "",
                    bodyMarkdown = "Corpo 2",
                    tags = emptyList(),
                    mentions = emptyList(),
                    language = Language.English,
                    createdAt = Instant.ofEpochMilli(1700000000000L),
                    updatedAt = Instant.ofEpochMilli(1700000000000L),
                    structured = true,
                )

            every { noteRepository.observeAll() } returns flowOf(listOf(note1, note2))
            every { noteRepository.observeAllTags() } returns flowOf(emptyList())

            val viewModel =
                NotesViewModel(noteRepository).apply {
                    // Route the export's launch through the test dispatcher so
                    // `advanceUntilIdle` waits for ZIP writing to finish before assertions.
                    // Production keeps Dispatchers.IO.
                    ioDispatcher = testDispatcher
                }
            testDispatcher.scheduler.advanceUntilIdle()

            // Selezioniamo entrambe
            viewModel.onIntent(NotesUiIntent.SelectAll)

            val outStream = ByteArrayOutputStream()
            viewModel.exportToZip { outStream }
            testDispatcher.scheduler.advanceUntilIdle()

            val zipBytes = outStream.toByteArray()
            assertThat(zipBytes).isNotEmpty()

            val zipIn = java.util.zip.ZipInputStream(zipBytes.inputStream())
            val entries = mutableListOf<String>()
            var entry = zipIn.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zipIn.nextEntry
            }
            zipIn.close()

            assertThat(entries).hasSize(2)
            // Verifichiamo che contengano id.take(6) e siano diversi nonostante stesso titolo/data
            assertThat(entries[0]).contains("Untitled_id-123.md")
            assertThat(entries[1]).contains("Untitled_id-876.md")
            assertThat(entries[0]).isNotEqualTo(entries[1])
        }
}
