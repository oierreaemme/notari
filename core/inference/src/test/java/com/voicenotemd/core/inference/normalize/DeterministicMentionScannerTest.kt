package com.voicenotemd.core.inference.normalize

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The mention BACKSTOP must be conservative: it only fires on unambiguous,
 * future-oriented datetime references that are literally present in the transcript
 * (review 2026-06-10 — "entro venerdì" / "on Monday morning" dropped by the model).
 * False positives are worse than misses: a junk chip on a note with no time
 * reference is exactly the failure ADR 0015's junk-mention filter exists to prevent.
 */
class DeterministicMentionScannerTest {
    // Wednesday 2026-06-10, 12:00 UTC.
    private val now = Instant.parse("2026-06-10T12:00:00Z")
    private val zone = ZoneOffset.UTC

    @Test
    fun `finds an embedded weekday and resolves it to the next occurrence`() {
        val result =
            DeterministicMentionScanner.scan(
                "Devo mandare il riepilogo a Luca entro venerdì.",
                "it",
                now,
                zone,
            )
        assertThat(result).hasSize(1)
        assertThat(result.first().surfaceForm).isEqualTo("venerdì")
        // Wednesday + 2 = Friday 2026-06-12, start of day.
        assertThat(result.first().resolved).isEqualTo(Instant.parse("2026-06-12T00:00:00Z"))
    }

    @Test
    fun `merges a day anchor with the clock time that follows it`() {
        val result =
            DeterministicMentionScanner.scan(
                "Domani alle 15 devo richiamare il dentista.",
                "it",
                now,
                zone,
            )
        assertThat(result).hasSize(1)
        assertThat(result.first().surfaceForm).isEqualTo("Domani alle 15")
        assertThat(result.first().resolved).isEqualTo(Instant.parse("2026-06-11T15:00:00Z"))
    }

    @Test
    fun `a standalone clock time resolves to its next future occurrence`() {
        // 9:00 is already past at noon → rolls to tomorrow 9:00.
        val result =
            DeterministicMentionScanner.scan(
                "Richiamare l'idraulico alle 9.",
                "it",
                now,
                zone,
            )
        assertThat(result).hasSize(1)
        assertThat(result.first().surfaceForm).isEqualTo("alle 9")
        assertThat(result.first().resolved).isEqualTo(Instant.parse("2026-06-11T09:00:00Z"))
    }

    @Test
    fun `a weekday with a past modifier is NOT scanned`() {
        val result =
            DeterministicMentionScanner.scan(
                "Venerdì scorso la riunione è andata male.",
                "it",
                now,
                zone,
            )
        assertThat(result).isEmpty()
    }

    @Test
    fun `conversational today-yesterday narration produces NO mentions`() {
        val result =
            DeterministicMentionScanner.scan(
                "Oggi è andata bene la riunione, meglio di ieri.",
                "it",
                now,
                zone,
            )
        assertThat(result).isEmpty()
    }

    @Test
    fun `a bare number is never a time`() {
        val result =
            DeterministicMentionScanner.scan(
                "Ho comprato 3 mele e 15 uova.",
                "it",
                now,
                zone,
            )
        assertThat(result).isEmpty()
    }

    @Test
    fun `longest phrase wins over its prefix`() {
        val result =
            DeterministicMentionScanner.scan(
                "Ci vediamo domani sera da Marco.",
                "it",
                now,
                zone,
            )
        assertThat(result).hasSize(1)
        assertThat(result.first().surfaceForm).isEqualTo("domani sera")
        // EVENING default = 20:00.
        assertThat(result.first().resolved).isEqualTo(Instant.parse("2026-06-11T20:00:00Z"))
    }

    @Test
    fun `english weekday works and 'last' guards the past`() {
        val found =
            DeterministicMentionScanner.scan(
                "I should email Sarah about it on monday.",
                "en",
                now,
                zone,
            )
        assertThat(found).hasSize(1)
        assertThat(found.first().surfaceForm).isEqualTo("monday")
        assertThat(found.first().resolved).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"))

        val guarded =
            DeterministicMentionScanner.scan(
                "Last friday was rough.",
                "en",
                now,
                zone,
            )
        assertThat(guarded).isEmpty()
    }

    @Test
    fun `results are capped and ordered by position`() {
        val result =
            DeterministicMentionScanner.scan(
                "Lunedì la spesa, martedì palestra, giovedì cena, sabato gita, domenica riposo.",
                "it",
                now,
                zone,
            )
        assertThat(result).hasSize(3)
        assertThat(result.map { it.surfaceForm }).containsExactly("Lunedì", "martedì", "giovedì").inOrder()
    }
}
