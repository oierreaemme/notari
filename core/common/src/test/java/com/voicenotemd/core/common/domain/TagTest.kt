package com.voicenotemd.core.common.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagTest {
    @Test
    fun `should normalize when given trimmed lowercase ascii`() {
        assertThat(Tag.normalize("ideas")?.value).isEqualTo("ideas")
    }

    @Test
    fun `should lowercase when given mixed case`() {
        assertThat(Tag.normalize("Ideas")?.value).isEqualTo("ideas")
        assertThat(Tag.normalize("WORK")?.value).isEqualTo("work")
    }

    @Test
    fun `should hyphenate when given spaces`() {
        assertThat(Tag.normalize("project plan")?.value).isEqualTo("project-plan")
        assertThat(Tag.normalize("  triple   space  ")?.value).isEqualTo("triple-space")
    }

    @Test
    fun `should drop diacritics and punctuation when given non-ascii input`() {
        // Note: we are aggressive — we strip non-ascii rather than transliterate. This is a
        // deliberate choice because cross-language tag matching is messy and out of scope for v1.
        assertThat(Tag.normalize("caffè!?")?.value).isEqualTo("caff")
    }

    @Test
    fun `should collapse multiple hyphens when given dirty input`() {
        assertThat(Tag.normalize("a--b---c")?.value).isEqualTo("a-b-c")
        assertThat(Tag.normalize("--leading and trailing--")?.value).isEqualTo("leading-and-trailing")
    }

    @Test
    fun `should return null when given empty or all-stripped input`() {
        assertThat(Tag.normalize(null)).isNull()
        assertThat(Tag.normalize("")).isNull()
        assertThat(Tag.normalize("   ")).isNull()
        assertThat(Tag.normalize("???")).isNull()
        assertThat(Tag.normalize("---")).isNull()
    }

    @Test
    fun `should truncate at MAX_LENGTH when given long input`() {
        val longInput = "a".repeat(50)
        val tag = Tag.normalize(longInput)
        assertThat(tag).isNotNull()
        assertThat(tag!!.value.length).isAtMost(Tag.MAX_LENGTH)
    }

    @Test
    fun `should not end with hyphen when truncation would leave one`() {
        val input = "abcdefghijklmnopqrstuvwxyzabcdef-extra"
        val tag = Tag.normalize(input)
        assertThat(tag).isNotNull()
        assertThat(tag!!.value).doesNotMatch(".*-$")
    }
}
