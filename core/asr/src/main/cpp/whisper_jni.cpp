#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Native bridge for com.voicenotemd.core.asr.WhisperContext. One whisper_context per
// instance; callers serialise access (one transcription at a time). ADR 0018 phase 2.

extern "C" JNIEXPORT jlong JNICALL
Java_com_voicenotemd_core_asr_WhisperContext_nativeInitFromFile(
        JNIEnv *env, jclass /*clazz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU only on-device (no reliable GPU backend on this target).
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voicenotemd_core_asr_WhisperContext_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ptr, jfloatArray audio,
        jint nThreads, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(audio);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(audio, 0, n, samples.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = false; // transcribe, never translate to English.
    params.n_threads = nThreads;
    params.language = lang; // "it" pins Italian; "auto" lets whisper detect.

    std::string result;
    if (whisper_full(ctx, params, samples.data(), static_cast<int>(n)) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) {
                result += text;
            }
        }
    } else {
        LOGE("whisper_full failed");
    }

    env->ReleaseStringUTFChars(language, lang);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicenotemd_core_asr_WhisperContext_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
