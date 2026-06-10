package com.voicenotemd.core.inference.normalize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixtures are the REAL round-4 bodies (2026-06-10) including their ASR garbling —
 * the prefix-tolerant matching exists precisely because the prose copy and the
 * checkbox copy of the same commitment routinely differ by transcription noise
 * ("rieffilogo"/"rieffogo", "Lucae"/"Luca").
 */
class CommitmentDeduplicatorTest {
    @Test
    fun `prose copy of a checkbox is dropped, surrounding prose survives`() {
        val body =
            "Oggi è andata bene la riunione col team. " +
                "Davo ricordarmi di mandare il rieffilogo a Lucae entro venerdì. " +
                "Poi volevo dire che il nuovo layout mi convince.\n\n" +
                "- [ ] Mandare il rieffogo a **Luca** entro venerdì"

        val result = CommitmentDeduplicator.dedupe(body)

        assertThat(result).isEqualTo(
            "Oggi è andata bene la riunione col team. " +
                "Poi volevo dire che il nuovo layout mi convince.\n\n" +
                "- [ ] Mandare il rieffogo a **Luca** entro venerdì",
        )
    }

    @Test
    fun `when the prose carries extra info the checkbox is upgraded before the drop`() {
        val body =
            "Domani alle 5 devo richiamare il dentista per spostare l'appuntamento\n\n" +
                "- [ ] Richiamare il dentista domani alle 5"

        val result = CommitmentDeduplicator.dedupe(body).trim()

        // No content lost: the fuller prose version (marker stripped) becomes the checkbox.
        assertThat(result).isEqualTo(
            "- [ ] Domani alle 5 richiamare il dentista per spostare l'appuntamento",
        )
    }

    @Test
    fun `unrelated prose next to a checkbox is untouched`() {
        val body =
            "Allora, non ricordo bene se la scadenza è il 20 o il 21.\n\n" +
                "- [ ] Controllare la mail di **Anna**"

        assertThat(CommitmentDeduplicator.dedupe(body)).isEqualTo(body)
    }

    @Test
    fun `body without checkboxes passes through unchanged`() {
        val body = "Solo prosa. Nessun impegno qui, solo pensieri in libertà."
        assertThat(CommitmentDeduplicator.dedupe(body)).isEqualTo(body)
    }

    @Test
    fun `checkbox lines themselves are never modified by the prose scan`() {
        val body =
            "- [ ] Comprare pane\n" +
                "- [ ] Comprare latte\n" +
                "- [ ] Comprare uova"
        assertThat(CommitmentDeduplicator.dedupe(body)).isEqualTo(body)
    }
}
