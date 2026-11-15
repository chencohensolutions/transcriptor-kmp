// The JNI bridge to whisper.cpp for WhisperEngine.kt: load a model (its encoder on the CPU or the NPU), transcribe
// 16 kHz mono samples, free.
#include <jni.h>
#include <string>
#include <algorithm>
#include <android/log.h>
#include <dlfcn.h>
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

// The encoder on Qualcomm's NPU (an encoder.bin from sherpa-onnx's Whisper QNN export), run through the C API our
// sherpa-onnx patch exports from libsherpa-onnx-jni.so (scripts/npu/sherpa-onnx-v2.0.1-qnn.patch); whisper.cpp
// decodes from its cross-attention K/V (third_party/whisper.cpp-external-encoder.patch).
struct NpuEncoder {
    void *(*create)(const char *, const char *, const char *) = nullptr;
    int (*run)(void *, const float *, int32_t, int32_t) = nullptr;
    int (*cross)(void *, int32_t, float *, float *, int32_t) = nullptr;
    void (*destroy)(void *) = nullptr;
    void *handle = nullptr;
    int32_t n = 0; // floats in one layer's K (frames x state)
};

// What a handle points to: the model, and the NPU encoder when there is one.
struct Engine {
    whisper_context *ctx = nullptr;
    NpuEncoder *npu = nullptr;
};

static bool npu_encode(void *user, const float *mel, int n_mel, int n_frames) {
    auto *e = static_cast<NpuEncoder *>(user);
    return e->run(e->handle, mel, n_mel, n_frames) == 1;
}

static bool npu_cross(void *user, int il, float *k, float *v) {
    auto *e = static_cast<NpuEncoder *>(user);
    return e->cross(e->handle, il, k, v, e->n) == 1;
}

static void free_engine(Engine *engine) {
    if (!engine) return;
    if (engine->ctx) whisper_free(engine->ctx);
    if (engine->npu) {
        if (engine->npu->handle) engine->npu->destroy(engine->npu->handle);
        delete engine->npu;
    }
    delete engine;
}

// Loads the model at `path`; with `encoder` (an encoder.bin) its encoder runs on the NPU, with Qualcomm's `backend`
// (libQnnHtp.so) and `system` (libQnnSystem.so) libraries: the NPU encoder is created first (its load needs more
// memory than it keeps), then whisper.cpp without its own encoder's weights. 0 on failure (the reason is in the log).
extern "C" JNIEXPORT jlong JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeInit(
        JNIEnv *env, jclass, jstring path, jstring encoder, jstring backend, jstring system) {
    auto *engine = new Engine();
    if (encoder) {
        // Loaded already by the app (System.loadLibrary("sherpa-onnx-jni")); this only finds it.
        void *lib = dlopen("libsherpa-onnx-jni.so", RTLD_NOW);
        auto *npu = new NpuEncoder();
        engine->npu = npu;
        if (lib) {
            npu->create = reinterpret_cast<decltype(npu->create)>(dlsym(lib, "SherpaOnnxQnnWhisperEncoderCreate"));
            npu->run = reinterpret_cast<decltype(npu->run)>(dlsym(lib, "SherpaOnnxQnnWhisperEncoderRun"));
            npu->cross = reinterpret_cast<decltype(npu->cross)>(dlsym(lib, "SherpaOnnxQnnWhisperEncoderCross"));
            npu->destroy = reinterpret_cast<decltype(npu->destroy)>(dlsym(lib, "SherpaOnnxQnnWhisperEncoderDestroy"));
        }
        if (!npu->create || !npu->run || !npu->cross || !npu->destroy) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "no NPU encoder in libsherpa-onnx-jni.so: %s", dlerror());
            free_engine(engine);
            return 0;
        }
        const char *e = env->GetStringUTFChars(encoder, nullptr);
        const char *b = env->GetStringUTFChars(backend, nullptr);
        const char *s = env->GetStringUTFChars(system, nullptr);
        npu->handle = npu->create(e, b, s);
        env->ReleaseStringUTFChars(encoder, e);
        env->ReleaseStringUTFChars(backend, b);
        env->ReleaseStringUTFChars(system, s);
        if (!npu->handle) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "the NPU encoder didn't load");
            free_engine(engine);
            return 0;
        }
    }
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = true;
    params.skip_encoder = engine->npu != nullptr;
    engine->ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    if (!engine->ctx) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load the model");
        free_engine(engine);
        return 0;
    }
    if (engine->npu) {
        engine->npu->n = whisper_model_n_audio_ctx(engine->ctx) * whisper_model_n_text_state(engine->ctx);
        whisper_set_external_encoder(engine->ctx, npu_encode, npu_cross, engine->npu);
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_engine(reinterpret_cast<Engine *>(handle));
}

// Returns "t0\tt1\tno_speech\ttext" per segment, times in milliseconds, no_speech the model's probability that
// there was no speech (0..1); null if whisper_full failed.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_chencohensolution_transcriptor_speech_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language, jstring prompt, jint threads) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    auto *ctx = engine->ctx;
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
    // (An NPU encoder has a fixed 30 s window: whisper.cpp ignores audio_ctx then.)
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
