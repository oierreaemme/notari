package com.voicenotemd.core.asr

import android.content.Context
import android.util.Log
import com.voicenotemd.core.common.domain.Language
import org.vosk.Model
import java.io.File

/**
 * Resolves and caches the on-device Vosk acoustic model for a language.
 *
 * Models are unpacked one directory per language under
 * `filesDir/vosk-models/<bcp47>` (e.g. `vosk-models/it`). Model *delivery*
 * (bundle vs download) is a separate concern tracked against ADR 0008 / ADR 0018;
 * this provider only locates and loads what is already on disk.
 */
interface VoskModelProvider {
    /** True when a usable model directory exists for [language]. */
    fun hasModel(language: Language): Boolean

    /**
     * Load — and cache — the model for [language], or null if absent. Loading is
     * expensive (reads the model into native memory), so the result is cached;
     * switching language closes the previously cached model first.
     */
    fun loadModel(language: Language): Model?
}

class FileVoskModelProvider(
    private val context: Context,
) : VoskModelProvider {
    private val lock = Any()
    private var cachedLanguage: Language? = null
    private var cachedModel: Model? = null

    override fun hasModel(language: Language): Boolean = resolveModelDir(language) != null

    override fun loadModel(language: Language): Model? =
        synchronized(lock) {
            if (cachedLanguage == language && cachedModel != null) return cachedModel
            val dir = resolveModelDir(language) ?: return null
            cachedModel?.close()
            cachedModel = null
            cachedLanguage = null
            val model = runCatching { Model(dir.absolutePath) }.getOrNull()
            if (model != null) {
                cachedModel = model
                cachedLanguage = language
            }
            model
        }

    /**
     * The model lives under `vosk-models/<bcp47>` and a real model directory always
     * contains an `am/` folder. We check the app's EXTERNAL files dir first — so a model
     * can be `adb push`ed during the spike without root/run-as — and fall back to the
     * INTERNAL files dir, where production delivery (bundle/download) will place it.
     * Returns the first location that looks like a real model, or null.
     */
    private fun resolveModelDir(language: Language): File? {
        val candidates = candidateRoots().map { File(File(it, MODELS_SUBDIR), language.bcp47) }
        // Diagnostic (spike): log exactly where we look and what we find, so a missing
        // model is debuggable without guessing about storage paths.
        candidates.forEach { dir ->
            Log.i(
                TAG,
                "candidate=${dir.absolutePath} dirExists=${dir.isDirectory} " +
                    "amExists=${File(dir, "am").isDirectory}",
            )
        }
        return candidates.firstOrNull { it.isDirectory && File(it, "am").isDirectory }
    }

    private fun candidateRoots(): List<File> = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)

    private companion object {
        const val MODELS_SUBDIR = "vosk-models"
        const val TAG = "VoskModel"
    }
}
