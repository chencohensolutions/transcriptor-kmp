// The JNI bridge to whisper.cpp for WhisperEngine.kt: load a model, transcribe 16 kHz mono samples, free.
#include <jni.h>
#include <string>
#include <algorithm>
#include <android/log.h>
#include "whisper.h"

#define TAG "whisper"

int whisper_quantize_main(int argc, char **argv);

// Quantizes the model at `in` to `out` ("q8_0", ...); true on success.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeQuantize(JNIEnv *env, jclass, jstring in, jstring out, jstring type) {
    const char *i = env->GetStringUTFChars(in, nullptr);
    const char *o = env->GetStringUTFChars(out, nullptr);
    const char *t = env->GetStringUTFChars(type, nullptr);
    std::string a0 = "quantize", a1 = i, a2 = o, a3 = t;
    char *argv[] = {a0.data(), a1.data(), a2.data(), a3.data()};
    int result = whisper_quantize_main(4, argv);
    env->ReleaseStringUTFChars(in, i);
    env->ReleaseStringUTFChars(out, o);
    env->ReleaseStringUTFChars(type, t);
    return result == 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load the model");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong ctx) {
    whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

// Returns "t0\tt1\tno_speech\ttext" per segment, times in milliseconds, no_speech the model's probability that
// there was no speech (0..1); null if whisper_full failed.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.suppress_nst = true;
    // The encoder's window fitted to the audio (50 per second, plus a margin) instead of 30 s: several times faster
    // for a line of a few seconds. Never under 8 s: with less, the model invents and repeats.
    jsize count_samples = env->GetArrayLength(samples);
    params.audio_ctx = std::min(1500, std::max(400, (int) (count_samples * 50LL / 16000) + 64));
    // About what fast Hebrew speech needs (8 tokens a second) and a margin: a model stuck repeating a phrase stops
    // there instead of decoding hundreds of tokens.
    params.max_tokens = (int) (count_samples * 10LL / 16000) + 12;
    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    const char *initial = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    params.initial_prompt = initial;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int result = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (initial) env->ReleaseStringUTFChars(prompt, initial);
    if (result != 0) return nullptr;

    int count = whisper_full_n_segments(ctx);
    jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; i++) {
        std::string line = std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
                           std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "\t" +
                           std::to_string(whisper_full_get_segment_no_speech_prob(ctx, i)) + "\t" +
                           whisper_full_get_segment_text(ctx, i);
        jstring s = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}
