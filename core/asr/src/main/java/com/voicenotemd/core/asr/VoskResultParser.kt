package com.voicenotemd.core.asr

/**
 * Pure, dependency-free extractor for the small JSON shapes Vosk emits.
 *
 * Kept free of any Android or Vosk import so it can be unit-tested on the plain
 * JVM (no Robolectric). Vosk returns:
 *  - partials: `{"partial" : "ciao come stai"}`
 *  - finals:   `{"text" : "ciao come stai", "result": [ ... ]}`
 *
 * We only need the spoken text; the per-word timing array is ignored.
 */
internal object VoskResultParser {
    fun finalText(json: String): String = extractStringField(json, "text")

    fun partialText(json: String): String = extractStringField(json, "partial")

    /**
     * Extract the value of a top-level string [field] from [json]. Returns "" when the
     * field is absent or empty. Handles whitespace around the colon and `\"` / `\\`
     * escapes inside the value. Deliberately minimal — Vosk's output is simple and
     * stable, so a full JSON parser would be overkill (and would pull a dependency
     * into a class we want JVM-testable).
     */
    private fun extractStringField(
        json: String,
        field: String,
    ): String {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return ""

        var i = keyIdx + key.length
        while (i < json.length && json[i] != ':') i++
        i++ // past ':'
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return ""
        i++ // past opening quote

        val sb = StringBuilder()
        var escaped = false
        while (i < json.length) {
            val c = json[i]
            if (escaped) {
                sb.append(
                    when (c) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> c
                    },
                )
                escaped = false
            } else {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> return sb.toString().trim()
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString().trim()
    }
}
