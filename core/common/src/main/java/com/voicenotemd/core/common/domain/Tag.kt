package com.voicenotemd.core.common.domain

/**
 * A short, lowercase, hyphenated label that we extract from the transcript.
 *
 * Tags are normalized aggressively at the boundary: the model is allowed to be sloppy,
 * the domain is not. Validation happens in [Tag.invoke] which is the only way to
 * construct a [Tag] (the data class constructor is private).
 */
@JvmInline
value class Tag private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 32
        private val ALLOWED = Regex("[^a-z0-9-]")
        private val MULTI_HYPHEN = Regex("-+")

        /**
         * Returns a normalized [Tag] or null if the input cannot be salvaged.
         * The model output is never trusted directly — we always run it through here.
         */
        fun normalize(raw: String?): Tag? {
            if (raw.isNullOrBlank()) return null
            val cleaned =
                raw.trim()
                    .lowercase()
                    .replace(' ', '-')
                    .replace(ALLOWED, "")
                    .replace(MULTI_HYPHEN, "-")
                    .trim('-')
            if (cleaned.isEmpty()) return null
            val truncated = cleaned.take(MAX_LENGTH).trim('-')
            if (truncated.isEmpty()) return null
            return Tag(truncated)
        }
    }
}
