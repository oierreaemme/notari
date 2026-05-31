package com.voicenotemd.core.inference.normalize

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Tag
import org.junit.Test

/**
 * Tests for the tag validation rules described in [TagValidator].
 *
 * The current policy (2026-05-16, after the "75% empty tags" regression):
 *
 *  - Multi-part kebab tags (`app-development`): strict word-boundary anchor
 *    on any ≥3-char part. Catches the "app matches inside appuntamento" bug.
 *  - Mono-part ≥4-char tags (`lavoro`, `personale`, `sogni`): TRUSTED, kept
 *    unconditionally. Catches semantic abstractions that have no literal
 *    anchor in the transcript.
 *  - Mono-part ≤3-char tags (`rag`, `seo`, `ai`): strict word-boundary anchor.
 *    Catches short context-bleed hallucinations.
 *
 * Accent folding (NFD + diacritic strip) applies to the transcript before
 * matching, so ASCII kebab tags match accented words across all Latin
 * scripts (it/es/fr/de/pt).
 */
class TagValidatorTest {
    @Test
    fun `tag literally present in transcript is kept`() {
        val transcript = "Riunione con Marco per il progetto."
        val tags = listOfTags("riunione")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("riunione")
    }

    @Test
    fun `tag matched case-insensitively is kept`() {
        val transcript = "Stiamo lavorando su Lighthouse questa settimana."
        val tags = listOfTags("lighthouse")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("lighthouse")
    }

    @Test
    fun `multi-word kebab tag whose part appears as word in transcript is kept`() {
        val transcript = "Idea per l'app: aggiungere un widget."
        val tags = listOfTags("app-development")
        val out = TagValidator.validate(tags, transcript)
        // "app" appears as a standalone word (after the apostrophe boundary).
        assertThat(out.map { it.value }).containsExactly("app-development")
    }

    @Test
    fun `short mono-part hallucinated tag with no anchor is stripped`() {
        // The original "rag" hallucination case: short context-bleed tag,
        // no transcript anchor → stripped under the strict ≤3-char rule.
        val transcript = "Sync con Sarah sul roadmap del prossimo quarter."
        val tags = listOfTags("rag")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `mono-part 4plus-char tag is trusted as semantic abstraction`() {
        // "filosofia" doesn't appear in the transcript, but Gemma chose it as
        // a topical abstraction. Under the new policy we trust mono-part
        // tags ≥4 chars. False positives are accepted as the price of
        // having tag arrays that aren't 75% empty.
        val transcript = "Pensieri sul significato della vita."
        val tags = listOfTags("filosofia")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("filosofia")
    }

    @Test
    fun `mixed valid and invalid tags — short hallucination stripped, others kept`() {
        val transcript = "Riunione con Marco per Lighthouse."
        val tags = listOfTags("riunione", "rag", "lighthouse", "fantasia")
        val out = TagValidator.validate(tags, transcript)
        // "riunione" and "lighthouse" anchor as words. "fantasia" passes by
        // the ≥4-char trust rule. Only "rag" (short, no anchor) is stripped.
        assertThat(out.map { it.value })
            .containsExactly("riunione", "lighthouse", "fantasia").inOrder()
    }

    @Test
    fun `duplicate tags are deduplicated`() {
        val transcript = "Riunione con il team."
        val tags = listOfTags("riunione", "riunione")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("riunione")
    }

    @Test
    fun `empty tags list returns empty`() {
        val out = TagValidator.validate(emptyList(), "transcript qualsiasi")
        assertThat(out).isEmpty()
    }

    @Test
    fun `multi-part tag with part shorter than 3 chars does NOT anchor on that part`() {
        // "ai" is too short to count as an anchor (false positives on common particles).
        // "research" doesn't appear in the transcript. Short multi-part fails.
        val transcript = "Sto andando a casa."
        val tags = listOfTags("ai-research")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `multi-part tag with at least one part 3plus chars matching transcript is kept`() {
        val transcript = "Sto facendo research sull'AI generativa."
        val tags = listOfTags("ai-research")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("ai-research")
    }

    @Test
    fun `short mono-part tag matched literally is kept`() {
        val transcript = "Ottimizzando il SEO della pagina."
        val tags = listOfTags("seo")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("seo")
    }

    // ---- Word-boundary correctness across languages (multi-part tags) ----

    @Test
    fun `multi-part tag does NOT match as substring inside longer word — italian`() {
        // Original bug: `app-development` got kept on a Jira/dentist note
        // because "app" substring-matched inside "appuntamento".
        val transcript = "Riunione con Marco. Poi appuntamento dal dentista."
        val tags = listOfTags("app-development")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `short tag does NOT match as substring inside longer word — english`() {
        val transcript = "The apple pie was great. Will share the recipe."
        val tags = listOfTags("app")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `short tag does NOT match as substring inside longer word — french`() {
        val transcript = "Une application intéressante pour les randonneurs."
        val tags = listOfTags("app")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `short tag does NOT match as substring inside longer word — spanish`() {
        val transcript = "Voy a apoyar la propuesta."
        val tags = listOfTags("app")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out).isEmpty()
    }

    @Test
    fun `short tag DOES match as standalone word — italian`() {
        val transcript = "Idea per l'app: aggiungere widget."
        val tags = listOfTags("app")
        // "l'app" — apostrophe is a non-letter boundary, so "app" matches.
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("app")
    }

    @Test
    fun `short tag DOES match as standalone word — english`() {
        val transcript = "Built an app for note-taking."
        val tags = listOfTags("app")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("app")
    }

    // ---- Accent folding for languages with diacritics ----

    @Test
    fun `accent-folded short tag matches accented transcript — italian perche`() {
        val transcript = "Ho letto perché ho tempo."
        val tags = listOfTags("perche")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("perche")
    }

    @Test
    fun `apostrophe acts as word boundary — italian elision`() {
        val transcript = "Mi è venuta l'idea di scrivere un libro."
        val tags = listOfTags("idea")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("idea")
    }

    @Test
    fun `apostrophe acts as word boundary — english possessive`() {
        val transcript = "Sarah's review was very thorough."
        val tags = listOfTags("sarah")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("sarah")
    }

    // ---- Semantic abstractions trusted at ≥4 chars ----

    @Test
    fun `semantic abstraction tag is kept — italian sogni for a dream note`() {
        // Transcript has "sogno"/"svegliato" — no literal "sogni". Under the
        // new trust policy, the abstraction passes.
        val transcript = "Stanotte ho fatto un sogno strano. Mi sono svegliato confuso."
        val tags = listOfTags("sogni")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("sogni")
    }

    @Test
    fun `semantic abstraction tag is kept — italian lavoro for a work note`() {
        val transcript = "Sto lavorando tutto il giorno su questo progetto."
        val tags = listOfTags("lavoro")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("lavoro")
    }

    @Test
    fun `semantic abstraction tag is kept — english thoughts for a thinking note`() {
        // Under the previous prefix-match rule this case failed because "thou"
        // doesn't start "thinking". Under the trust rule it passes.
        val transcript = "I am thinking about the next project."
        val tags = listOfTags("thoughts")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("thoughts")
    }

    @Test
    fun `semantic abstraction tag is kept across language gap — riflessione for réflexion`() {
        // Cross-lingual: tag "riflessione" (Italian) on a French transcript
        // with "réflexion". Trust policy ignores the lexical gap.
        val transcript = "Une réflexion profonda sulla giornata."
        val tags = listOfTags("riflessione")
        val out = TagValidator.validate(tags, transcript)
        assertThat(out.map { it.value }).containsExactly("riflessione")
    }
}

private fun listOfTags(vararg names: String): List<Tag> = names.mapNotNull(Tag.Companion::normalize)
