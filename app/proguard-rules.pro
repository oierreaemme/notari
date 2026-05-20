# Keep MediaPipe Tasks GenAI native bindings.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Hilt-generated code.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Moshi reflective adapters (for the few non-codegen models we keep).
-keep class kotlin.Metadata { *; }
-keepclasseswithmembers class * { @com.squareup.moshi.JsonClass <init>(...); }
