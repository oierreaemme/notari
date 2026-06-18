# ── LiteRT-LM (Google AI Edge) ────────────────────────────────────────────────
# The LiteRT-LM native .so resolves Java class names and method/field IDs at
# runtime via JNI FindClass/GetMethodID/GetFieldID. R8 was obfuscating and
# class-merging the entire com.google.ai.edge.litertlm.** package, which caused
# FindClass("com/.../ConversationConfig") to return null → SIGSEGV → instant
# process kill every time the user stopped a recording. Keep ALL LiteRT-LM
# classes with their original names so JNI lookups always succeed.
# See R8 mapping: ConversationConfig → R8$$REMOVED$$CLASS$$503,
#                 Content → R8$$REMOVED$$CLASS$$501 (both now fixed by this rule).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# MediaPipe / GMS: NOT direct dependencies anymore (LiteRT-LM migration, ADR 0008).
# The old blanket `-keep class … { *; }` rules disabled R8 shrinking/obfuscation for
# anything those packages pulled in transitively, inflating the APK for no benefit
# (review 2026-06-10 #14). `-dontwarn` is enough: if a transitive artifact references
# these classes on a code path we never execute, R8 should neither fail the build nor
# be forced to keep them. If MediaPipe returns as a direct dependency (multimodal
# upgrade path), reintroduce targeted keeps for the classes its JNI actually looks up.
-dontwarn com.google.mediapipe.**
-dontwarn com.google.android.gms.**

# Keep Hilt-generated code.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Moshi reflective adapters (for the few non-codegen models we keep).
-keep class kotlin.Metadata { *; }
-keepclasseswithmembers class * { @com.squareup.moshi.JsonClass <init>(...); }

# ── Strip diagnostic Logs from the release build ──────────────────────────────
# Spike-era informational logging stays *active in debug* (so we can pull
# logcat for on-device troubleshooting — BatchSession timings, AsrBtRouter
# routing decisions, WhisperBatch model loads, etc.) and gets stripped here.
# `-assumenosideeffects` tells R8 the call returns nothing observable, so it
# removes both the call AND any argument computation (string concatenation
# in the log message), giving zero runtime overhead. Log.e/Log.w stay — a
# real production failure still surfaces in logcat. See ADR 0021.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
