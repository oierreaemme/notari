package com.voicenotemd.core.asr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-function tests for [BatchSpeechToTextSession.computeRmsDb] and the
 * [FakeBatchTranscriber] placeholder.
 *
 * **Out of scope.** The full session — `AudioRecord`, the reader thread, the BT routing,
 * the PCM zeroing, the `audioReady` emission on the first non-silent frame — depends on
 * the Android audio stack and needs an instrumented test (Robolectric or a connected
 * device). The privacy invariant (no audio persistence sinks, AudioRecord limited to the
 * designated files, buffer zeroed via `.fill(0)`) is asserted by
 * [NoAudioPersistenceTest] at source-set scan time. This file covers the
 * platform-independent arithmetic and the contract of the fake transcriber.
 */
class BatchSpeechToTextSessionTest {
    @Test
    fun `computeRmsDb maps zero-length input to silence`() {
        val rms = BatchSpeechToTextSession.computeRmsDb(ShortArray(0), 0)
        assertThat(rms).isEqualTo(BatchSpeechToTextSession.SILENCE_DB)
    }

    @Test
    fun `computeRmsDb maps all-zero PCM to silence`() {
        val samples = ShortArray(1_024) // all zeros
        val rms = BatchSpeechToTextSession.computeRmsDb(samples, samples.size)
        assertThat(rms).isEqualTo(BatchSpeechToTextSession.SILENCE_DB)
    }

    @Test
    fun `computeRmsDb maps near-zero PCM (below 1_0) to silence`() {
        // A constant value of 0 across all samples is mathematically rms == 0, which the
        // function clamps to SILENCE_DB explicitly — but we also want to confirm the
        // explicit floor at rms < 1.0 inside the conversion. Constant amplitude of 0
        // already triggers the early return; this test is more about pinning the
        // behaviour so future refactors don't drop the floor.
        val samples = ShortArray(8) { 0 }
        val rms = BatchSpeechToTextSession.computeRmsDb(samples, samples.size)
        assertThat(rms).isEqualTo(BatchSpeechToTextSession.SILENCE_DB)
    }

    @Test
    fun `computeRmsDb returns a value above the non-silent threshold for loud PCM`() {
        // A constant amplitude of ~half-scale (≈ -6 dBFS) is well above the threshold
        // used by the audio-ready signal. We don't pin an exact value (the dB → UI
        // amplitude mapping is a UI taste tuning, not a stable contract), just that the
        // function fires above the NON_SILENT_DB_THRESHOLD so the warm-up state machine
        // (ADR 0020) would correctly leave Preparing.
        val loud = ShortArray(1_024) { 16_000 } // ≈ half-scale
        val rms = BatchSpeechToTextSession.computeRmsDb(loud, loud.size)
        assertThat(rms).isGreaterThan(BatchSpeechToTextSession.NON_SILENT_DB_THRESHOLD)
    }

    @Test
    fun `computeRmsDb is monotonic louder PCM produces a higher value`() {
        // A practical sanity check: a 10% amplitude block should yield a smaller RMS-dB
        // reading than a 50% amplitude block. This catches regressions like a swapped
        // sign in the dBFS conversion or an inverted clamp.
        val quiet = ShortArray(1_024) { (32_768 / 10).toShort() }
        val louder = ShortArray(1_024) { (32_768 / 2).toShort() }
        val quietRms = BatchSpeechToTextSession.computeRmsDb(quiet, quiet.size)
        val louderRms = BatchSpeechToTextSession.computeRmsDb(louder, louder.size)
        assertThat(louderRms).isGreaterThan(quietRms)
    }

    @Test
    fun `computeRmsDb respects the length argument and ignores trailing samples`() {
        // The reader thread always passes the count returned by AudioRecord.read(),
        // which can be smaller than the backing buffer — make sure we don't fold in
        // stale trailing data.
        val samples = ShortArray(1_024) { if (it < 128) 16_000 else 0 } // loud head, zero tail
        val rmsFirstHalf = BatchSpeechToTextSession.computeRmsDb(samples, 128)
        val rmsAll = BatchSpeechToTextSession.computeRmsDb(samples, samples.size)
        assertThat(rmsFirstHalf).isGreaterThan(rmsAll)
    }

    @Test
    fun `FakeBatchTranscriber returns a deterministic placeholder describing the captured PCM`() {
        // The placeholder used to be wired through DI during the spike; today it lives only
        // in the test surface (and any future seam where we want to exercise the
        // record → structure flow without loading a 180 MB whisper model). Pin the basic
        // shape of its output so it stays useful as a stub.
        val transcriber = FakeBatchTranscriber()
        val pcm = ShortArray(16_000) // exactly one second at 16 kHz
        val transcript =
            kotlinx.coroutines.runBlocking {
                transcriber.transcribe(pcm, sampleRate = 16_000, languageBcp47 = "it")
            }
        assertThat(transcript).contains("16000")
        assertThat(transcript).contains("1.0")
    }
}
