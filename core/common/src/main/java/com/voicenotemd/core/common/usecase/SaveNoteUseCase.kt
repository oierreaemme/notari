package com.voicenotemd.core.common.usecase

import com.voicenotemd.core.common.domain.Note

/**
 * Persists a freshly structured [Note]. Encapsulates the (very thin) write side of the
 * note repository so that the capture flow doesn't reach into the repository directly.
 */
interface SaveNoteUseCase {
    suspend operator fun invoke(note: Note)
}
