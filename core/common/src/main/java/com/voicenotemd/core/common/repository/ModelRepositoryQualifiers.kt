package com.voicenotemd.core.common.repository

import javax.inject.Qualifier

/**
 * Hilt qualifiers distinguishing the two on-device model repositories, both of which
 * implement [OnDeviceModelRepository]:
 *
 *  - [GemmaModel] — the Gemma 4 E2B `.litertlm` used for note structuring.
 *  - [WhisperModel] — the whisper.cpp `ggml-*.bin` used for transcription.
 *
 * They live in `:core:common` so the bindings (`:app`) and the injection site
 * (`:feature:settings`) reference the same annotations. See ADR 0022 (model delivery).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GemmaModel

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WhisperModel
