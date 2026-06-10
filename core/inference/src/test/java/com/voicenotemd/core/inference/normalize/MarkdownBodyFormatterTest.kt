package com.voicenotemd.core.inference.normalize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownBodyFormatterTest {
    @Test
    fun `inline checkbox after prose is moved to its own line`() {
        val input = "Mi sono svegliato confuso. - [ ] Ricordarmi di parlarne a Sofia."
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Mi sono svegliato confuso.\n\n- [ ] Ricordarmi di parlarne a Sofia.",
        )
    }

    @Test
    fun `inline bullet after prose is moved to its own line`() {
        val input = "Spesa di domani: - pane - latte - mele"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Spesa di domani:\n\n- pane\n- latte\n- mele",
        )
    }

    @Test
    fun `already correctly formatted checkbox block is left alone`() {
        val input = "Riunione domani.\n\n- [ ] Preparare slide\n- [ ] Mandare agenda"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `a line with unpaired bold markers loses bold entirely`() {
        // Real-device 2026-06-10: "…budget review** on **Monday morning**" — odd
        // marker count leaks literal asterisks in every renderer.
        val input = "Quick thought I should email **Sarah** about the budget review** on **Monday morning**"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Quick thought I should email Sarah about the budget review on Monday morning",
        )
    }

    @Test
    fun `balanced bold is left untouched and other lines are independent`() {
        val input = "Una riga con **grassetto** corretto.\nRiga rotta con **metà"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Una riga con **grassetto** corretto.\nRiga rotta con metà",
        )
    }

    @Test
    fun `triple newlines collapse to double`() {
        val input = "Prima riga.\n\n\n\nSeconda riga."
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo("Prima riga.\n\nSeconda riga.")
    }

    @Test
    fun `trailing whitespace per line is stripped`() {
        // Note: the expected output also reflects `ensureBlankLineBeforeBlocks`,
        // which inserts a blank line between a prose line ("Riga due") and a
        // checkbox marker line ("- [ ] Task"). That blank-line behavior is
        // covered by its own tests below; this assertion is the joint outcome
        // of trailing-whitespace stripping AND block separation, which is what
        // the formatter does in a single pass on real input.
        val input = "Riga uno   \nRiga due\t\n- [ ] Task   "
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo("Riga uno\nRiga due\n\n- [ ] Task")
    }

    @Test
    fun `consecutive checkboxes stay on separate lines`() {
        val input = "- [ ] Uno\n- [ ] Due\n- [ ] Tre"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `mixed checkboxes and bullets stay separate`() {
        val input = "Lista:\n\n- [ ] Action item\n- Plain item\n- [x] Done"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `prose followed by heading gets blank line`() {
        // Headings count as block-starters too — but the formatter doesn't
        // currently add separators between prose and `## heading`. Let's
        // check what it does: should NOT crash, and should add separator
        // because headings are in startsBlock.
        val input = "Conclusione.\n## Sezione successiva"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo("Conclusione.\n\n## Sezione successiva")
    }

    @Test
    fun `empty input passes through unchanged`() {
        assertThat(MarkdownBodyFormatter.format("")).isEqualTo("")
        assertThat(MarkdownBodyFormatter.format("   ")).isEqualTo("   ")
    }

    @Test
    fun `dash inside word is not treated as bullet`() {
        // Negative lookahead in INLINE_BULLET requires \w after the dash + space,
        // but we should not break things like "ex-presidente" or compound words.
        val input = "Sto pensando all'ex-presidente del consiglio."
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `multiple inline checkboxes in one prose paragraph all break out`() {
        val input = "Devo fare tre cose oggi. - [ ] Pulire la cucina - [ ] Lavare i panni - [ ] Spesa"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Devo fare tre cose oggi.\n\n- [ ] Pulire la cucina\n- [ ] Lavare i panni\n- [ ] Spesa",
        )
    }

    @Test
    fun `idempotent — formatting twice yields the same result`() {
        val input = "Mi sono svegliato. - [ ] Telefonare a Sofia."
        val once = MarkdownBodyFormatter.format(input)
        val twice = MarkdownBodyFormatter.format(once)
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `preserves bold and inline markdown`() {
        val input = "Meeting con **Marco** alle 10. - [ ] Portare il **MacBook**"
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(
            "Meeting con **Marco** alle 10.\n\n- [ ] Portare il **MacBook**",
        )
    }

    @Test
    fun `bullet inside prose without following word char is left alone`() {
        // "5 - 3 = 2" should NOT be treated as a bullet split.
        val input = "Il calcolo è 5 - 3 = 2."
        val output = MarkdownBodyFormatter.format(input)
        assertThat(output).isEqualTo(input)
    }
}
