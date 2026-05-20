package com.voicenotemd.core.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.DateMention
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.database.VoiceNoteDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteRepositoryImplTest {
    private lateinit var db: VoiceNoteDatabase
    private lateinit var repo: NoteRepositoryImpl

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                VoiceNoteDatabase::class.java,
            ).allowMainThreadQueries().build()
        repo = NoteRepositoryImpl(db.noteDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `should round-trip a structured note when given full payload`() =
        runTest {
            val note = sampleNote(id = "n1", title = "Buy milk")
            repo.insert(note)

            repo.observe("n1").test {
                // Turbine 1.x's Flow.test { ... } returns Unit — the block's last expression
                // is discarded. We do all assertions inline, same pattern as the other tests
                // in this file.
                val stored = awaitItem()
                assertThat(stored).isNotNull()
                assertThat(stored!!.title).isEqualTo("Buy milk")
                assertThat(stored.tags.map { it.value }).containsExactly("errands")
                assertThat(stored.mentions).hasSize(1)
                assertThat(stored.mentions.first().resolved)
                    .isEqualTo(Instant.parse("2026-05-10T15:00:00Z"))
                assertThat(stored.language).isEqualTo(Language.English)
                assertThat(stored.structured).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `should emit notes ordered by createdAt desc when given multiple notes`() =
        runTest {
            repo.insert(sampleNote(id = "older", createdAt = Instant.parse("2026-05-01T00:00:00Z")))
            repo.insert(sampleNote(id = "newer", createdAt = Instant.parse("2026-05-09T00:00:00Z")))

            repo.observeAll().test {
                val v = awaitItem()
                assertThat(v.map { it.id }).containsExactly("newer", "older").inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `should filter by tag when querying observeByTag`() =
        runTest {
            // Distinct createdAt so the ORDER BY created_at DESC has unambiguous tie-breaking.
            // Without these, SQLite is free to return tied rows in any order (rowid order in
            // practice, but not contractually).
            repo.insert(
                sampleNote(
                    id = "a",
                    tags = listOf("work"),
                    createdAt = Instant.parse("2026-05-09T10:00:00Z"),
                ),
            )
            repo.insert(sampleNote(id = "b", tags = listOf("personal")))
            repo.insert(
                sampleNote(
                    id = "c",
                    tags = listOf("work", "urgent"),
                    createdAt = Instant.parse("2026-05-09T14:00:00Z"),
                ),
            )

            repo.observeByTag(Tag.normalize("work")!!).test {
                val v = awaitItem()
                assertThat(v.map { it.id }).containsExactly("c", "a").inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `should expose distinct tag values when notes share tags`() =
        runTest {
            repo.insert(sampleNote(id = "a", tags = listOf("work", "urgent")))
            repo.insert(sampleNote(id = "b", tags = listOf("work", "personal")))

            repo.observeAllTags().test {
                val v = awaitItem()
                assertThat(v.map { it.value }).containsExactly("personal", "urgent", "work").inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `should cascade-delete tags and mentions when a note is removed`() =
        runTest {
            repo.insert(sampleNote(id = "n1"))
            repo.delete("n1")

            repo.observe("n1").test {
                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            // Distinct tag list collapses to empty after the only note is gone.
            repo.observeAllTags().test {
                assertThat(awaitItem()).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteAll should remove every note`() =
        runTest {
            repo.insert(sampleNote(id = "a"))
            repo.insert(sampleNote(id = "b"))
            repo.deleteAll()

            repo.observeAll().test {
                assertThat(awaitItem()).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun sampleNote(
        id: String,
        title: String = "Title",
        tags: List<String> = listOf("errands"),
        createdAt: Instant = Instant.parse("2026-05-09T12:00:00Z"),
    ): Note =
        Note(
            id = id,
            title = title,
            bodyMarkdown = "Body for $id",
            tags = tags.mapNotNull(Tag.Companion::normalize),
            mentions =
                listOf(
                    DateMention("tomorrow at 3pm", Instant.parse("2026-05-10T15:00:00Z")),
                ),
            language = Language.English,
            createdAt = createdAt,
            updatedAt = createdAt,
            structured = true,
        )
}
