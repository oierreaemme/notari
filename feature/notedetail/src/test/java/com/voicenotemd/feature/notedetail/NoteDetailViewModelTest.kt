// A few lines in the inline anonymous-object NoteRepository stub exceed 120 chars after
// ktlint's `function-signature-expression-body` rule joins single-expression overrides
// onto the signature line. The expressions are short and self-explanatory; rewriting
// them to fit (extracted helpers, multi-line bodies, etc.) would add ceremony without
// improving the test. Suppress max-line-length for this file.
@file:Suppress("ktlint:standard:max-line-length")

package com.voicenotemd.feature.notedetail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import com.voicenotemd.core.common.usecase.StructuringResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NoteDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val notesFlow = MutableStateFlow<Map<String, Note>>(emptyMap())
    private val deleted = mutableListOf<String>()
    private val updates = mutableListOf<Note>()

    private lateinit var repository: NoteRepository

    // Configurable structuring stub: tests set [structureStub] to control the outcome.
    // null means "model unavailable" → the use case throws, exercising the failure path.
    private var structureStub: StructuringResult? = null
    private val capturedStructureTags = mutableListOf<List<String>>()
    private val structureNote =
        object : StructureNoteUseCase {
            override suspend fun invoke(
                transcript: String,
                forceLanguage: Language?,
                existingTags: List<String>,
            ): StructuringResult {
                capturedStructureTags += existingTags
                return structureStub ?: error("structuring unavailable")
            }
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository =
            object : NoteRepository {
                override fun observeAll(): Flow<List<Note>> = notesFlow.map { it.values.toList() }

                override fun observe(id: String): Flow<Note?> = notesFlow.map { it[id] }

                override fun observeByTag(tag: Tag): Flow<List<Note>> =
                    notesFlow.map { it.values.filter { n -> n.tags.contains(tag) } }

                override fun observeAllTags(): Flow<List<Tag>> =
                    notesFlow.map { it.values.flatMap(Note::tags).distinct() }

                override suspend fun insert(note: Note) {
                    notesFlow.value = notesFlow.value + (note.id to note)
                }

                override suspend fun update(note: Note) {
                    updates += note
                    insert(note)
                }

                override suspend fun delete(id: String) {
                    deleted += id
                    notesFlow.value = notesFlow.value - id
                }

                override suspend fun deleteAll() {
                    notesFlow.value = emptyMap()
                }
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `should expose note from repository when present`() =
        runTest {
            seed("abc")

            val vm = newViewModel("abc")

            advanceTimeBy(50)
            val state = vm.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.note?.id).isEqualTo("abc")
        }

    @Test
    fun `should set notFound when repository emits null`() =
        runTest {
            val vm = newViewModel("missing")

            advanceTimeBy(50)
            assertThat(vm.uiState.value.notFound).isTrue()
        }

    @Test
    fun `should enter edit and save updated note`() =
        runTest {
            seed("abc")
            val vm = newViewModel("abc")
            advanceTimeBy(50)

            vm.onIntent(NoteDetailUiIntent.EnterEdit)
            vm.onIntent(NoteDetailUiIntent.UpdateDraftTitle("New title"))
            vm.onIntent(NoteDetailUiIntent.UpdateDraftBody("Body changed"))
            vm.onIntent(NoteDetailUiIntent.SaveEdit)

            advanceTimeBy(50)
            val saved = updates.last()
            assertThat(saved.title).isEqualTo("New title")
            assertThat(saved.bodyMarkdown).isEqualTo("Body changed")
            assertThat(vm.uiState.value.isEditing).isFalse()
        }

    @Test
    fun `should restore drafts when CancelEdit fires`() =
        runTest {
            seed("abc")
            val vm = newViewModel("abc")
            advanceTimeBy(50)
            vm.onIntent(NoteDetailUiIntent.EnterEdit)
            vm.onIntent(NoteDetailUiIntent.UpdateDraftTitle("temp"))
            vm.onIntent(NoteDetailUiIntent.CancelEdit)

            val state = vm.uiState.value
            assertThat(state.isEditing).isFalse()
            assertThat(state.draftTitle).isEqualTo(state.note?.title)
        }

    @Test
    fun `should emit Closed event after Delete`() =
        runTest {
            seed("abc")
            val vm = newViewModel("abc")
            advanceTimeBy(50)

            vm.uiEvents.test {
                vm.onIntent(NoteDetailUiIntent.Delete)
                assertThat(awaitItem()).isEqualTo(NoteDetailUiEvent.Closed)
                cancelAndConsumeRemainingEvents()
            }
            assertThat(deleted).containsExactly("abc")
        }

    @Test
    fun `should emit ShareMarkdown with YAML frontmatter and body`() =
        runTest {
            val tag = Tag.normalize("focus")!!
            seed(id = "abc", title = "Hello", body = "Body text", tags = listOf(tag))
            val vm = newViewModel("abc")
            advanceTimeBy(50)

            vm.uiEvents.test {
                vm.onIntent(NoteDetailUiIntent.Share)
                val event = awaitItem() as NoteDetailUiEvent.ShareMarkdown
                assertThat(event.title).isEqualTo("Hello")
                // Share output now matches the ZIP export format — YAML frontmatter
                // followed by `# Title` heading and the body. See
                // `Note.toMarkdownWithFrontmatter` in :core:common.
                assertThat(event.markdown).startsWith("---\n")
                assertThat(event.markdown).contains("title: \"Hello\"")
                assertThat(event.markdown).contains("language: en")
                assertThat(event.markdown).contains("tags: [focus]")
                assertThat(event.markdown).contains("---\n\n# Hello")
                assertThat(event.markdown).contains("Body text")
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `Restructure overwrites the note when structuring succeeds`() =
        runTest {
            seed(id = "abc", title = "raw first line", body = "raw transcript text", structured = false)
            structureStub =
                StructuringResult(
                    note =
                        Note(
                            id = "ignored-new-id",
                            title = "Structured Title",
                            bodyMarkdown = "## Structured\n- bullet",
                            tags = listOf(Tag.normalize("work")!!),
                            mentions = emptyList(),
                            language = Language.English,
                            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            structured = true,
                        ),
                    lastRawResponse = null,
                )
            val vm = newViewModel("abc")
            advanceTimeBy(50)

            vm.onIntent(NoteDetailUiIntent.Restructure)
            advanceTimeBy(50)

            val saved = updates.last()
            assertThat(saved.id).isEqualTo("abc") // id + createdAt preserved
            assertThat(saved.createdAt).isEqualTo(Instant.parse("2026-05-09T12:00:00Z"))
            assertThat(saved.title).isEqualTo("Structured Title")
            assertThat(saved.bodyMarkdown).isEqualTo("## Structured\n- bullet")
            assertThat(saved.structured).isTrue()
            assertThat(vm.uiState.value.isRestructuring).isFalse()
            assertThat(vm.uiState.value.restructureError).isNull()
        }

    @Test
    fun `Restructure keeps the note and surfaces an error on fallback`() =
        runTest {
            seed(id = "abc", title = "raw", body = "raw transcript text", structured = false)
            structureStub =
                StructuringResult(
                    note =
                        Note(
                            id = "x",
                            title = "raw",
                            bodyMarkdown = "raw transcript text",
                            tags = emptyList(),
                            mentions = emptyList(),
                            language = Language.English,
                            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            structured = false, // fallback: model couldn't structure
                        ),
                    lastRawResponse = "{ broken json",
                )
            val vm = newViewModel("abc")
            advanceTimeBy(50)

            vm.onIntent(NoteDetailUiIntent.Restructure)
            advanceTimeBy(50)

            // No update committed; original note intact; a retry hint is shown.
            assertThat(updates).isEmpty()
            assertThat(vm.uiState.value.note?.structured).isFalse()
            assertThat(vm.uiState.value.restructureError).isNotNull()
            assertThat(vm.uiState.value.isRestructuring).isFalse()
        }

    private fun seed(
        id: String,
        title: String = "Some title",
        body: String = "Some body",
        tags: List<Tag> = emptyList(),
        structured: Boolean = true,
    ) {
        notesFlow.value = notesFlow.value + (
            id to
                Note(
                    id = id,
                    title = title,
                    bodyMarkdown = body,
                    tags = tags,
                    mentions = emptyList(),
                    language = Language.English,
                    createdAt = Instant.parse("2026-05-09T12:00:00Z"),
                    updatedAt = Instant.parse("2026-05-09T12:00:00Z"),
                    structured = structured,
                )
        )
    }

    private fun newViewModel(id: String): NoteDetailViewModel =
        NoteDetailViewModel(
            noteRepository = repository,
            structureNote = structureNote,
            savedStateHandle = SavedStateHandle(mapOf(NoteDetailViewModel.NOTE_ID_KEY to id)),
        ).apply {
            clock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC)
        }
}
