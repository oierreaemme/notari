package com.voicenotemd.core.asr

/**
 * Transcribes a complete in-RAM PCM buffer in one shot (batch), as opposed to the
 * streaming Vosk path. This is the seam for the whisper migration (ADR 0018): whisper.cpp
 * is window/chunk-based, so for a voice-note app the natural shape is "capture continuously,
 * transcribe once at stop".
 *
 * @param pcm 16-bit mono PCM samples captured at [sampleRate].
 * @param sampleRate sample rate of [pcm] (16 kHz in our pipeline).
 * @param languageBcp47 the dictation language ("it", "en", …) to steer the engine, or
 *   "auto" to let it detect.
 * @return the transcript text.
 */
interface BatchTranscriber {
    suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int,
        languageBcp47: String,
    ): String
}

/**
 * Phase-1 placeholder: ignores the audio and returns a fixed sentence (plus the captured
 * sample count / duration) so the end-to-end batch flow — record → transcribe → structure —
 * can be validated before the native whisper.cpp engine is integrated in phase 2.
 */
class FakeBatchTranscriber : BatchTranscriber {
    override suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int,
        languageBcp47: String,
    ): String {
        val seconds = if (sampleRate > 0) pcm.size.toDouble() / sampleRate else 0.0
        return "Nota di prova in modalità batch: catturati ${pcm.size} campioni PCM, " +
            "circa ${"%.1f".format(seconds)} secondi di audio. In fase 2 questo testo " +
            "sarà prodotto da whisper a partire dall'audio reale."
    }
}
