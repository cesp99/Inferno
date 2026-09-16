// JNI surface of libinferno_sd.so. Mirrors SdNative.kt exactly; keep both in sync.
//
// All entry points except cancel()/isLoaded() are called on ImageEngine's single worker
// thread. Callbacks into Java happen on whichever thread sd.cpp invokes us from (normally the
// same worker thread); ScopedEnv attaches/detaches when it is not.
#include <jni.h>

#include <cstdint>
#include <memory>
#include <string>

#include "sd_engine.h"
#include "stable-diffusion.h"

namespace {

JavaVM* g_vm = nullptr;
std::unique_ptr<inferno_sd::Engine> g_engine;
// Resolved once in JNI_OnLoad (System.loadLibrary runs with SdNative's class loader).
jmethodID g_on_progress = nullptr;
jmethodID g_on_preview = nullptr;
// Error text of the last failed load()/generate() (worker thread only).
std::string g_last_error;

class ScopedEnv {
public:
    ScopedEnv() {
        if (!g_vm) return;
        jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            JavaVMAttachArgs args{JNI_VERSION_1_6, "inferno_sd_cb", nullptr};
            if (g_vm->AttachCurrentThread(&env_, &args) == JNI_OK) attached_ = true;
            else env_ = nullptr;
        } else if (rc != JNI_OK) {
            env_ = nullptr;
        }
    }
    ~ScopedEnv() {
        if (attached_) g_vm->DetachCurrentThread();
    }
    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

// jstring -> UTF-8 via the UTF-16 view. GetStringUTFChars would hand us modified UTF-8 (CESU-8 surrogate
// pairs for U+10000+, C0 80 for NUL), which the CLIP byte-level BPE would tokenise differently from sd-cli.
std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    std::string out;
    if (!s) return out;
    const jsize n = env->GetStringLength(s);
    const jchar* u = env->GetStringChars(s, nullptr);
    if (!u) return out;
    out.reserve(static_cast<size_t>(n) * 3);
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;  // lone surrogate
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

struct ListenerCtx {
    jobject listener;  // global ref, valid for the duration of generate()
};

void on_progress(void* user, int step, int steps, float sec_per_step) {
    auto* lc = static_cast<ListenerCtx*>(user);
    if (!lc || !lc->listener || !g_on_progress) return;
    ScopedEnv se;
    JNIEnv* env = se.get();
    if (!env) return;
    env->CallVoidMethod(lc->listener, g_on_progress, (jint)step, (jint)steps, (jfloat)sec_per_step);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void on_preview(void* user, int step, int width, int height, const uint8_t* rgba) {
    auto* lc = static_cast<ListenerCtx*>(user);
    if (!lc || !lc->listener || !g_on_preview) return;
    ScopedEnv se;
    JNIEnv* env = se.get();
    if (!env) return;
    const jsize n = (jsize)((size_t)width * height * 4);
    jbyteArray arr = env->NewByteArray(n);
    if (!arr) { env->ExceptionClear(); return; }
    env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(rgba));
    env->CallVoidMethod(lc->listener, g_on_preview, (jint)step, (jint)width, (jint)height, arr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(arr);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass listener = env->FindClass("to/eyed/inferno/sd/SdNative$Listener");
    if (!listener) return JNI_ERR;
    g_on_progress = env->GetMethodID(listener, "onProgress", "(IIF)V");
    g_on_preview = env->GetMethodID(listener, "onPreview", "(III[B)V");
    env->DeleteLocalRef(listener);
    if (!g_on_progress || !g_on_preview) return JNI_ERR;
    g_engine = std::make_unique<inferno_sd::Engine>();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_to_eyed_inferno_sd_SdNative_systemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(sd_get_system_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_to_eyed_inferno_sd_SdNative_load(JNIEnv* env, jclass, jstring modelPath, jstring taesdPath,
                                      jint threads, jboolean flashAttn) {
    inferno_sd::LoadParams p;
    p.model_path = jstring_to_utf8(env, modelPath);
    p.taesd_path = jstring_to_utf8(env, taesdPath);
    p.n_threads = threads;
    p.diffusion_flash_attn = flashAttn == JNI_TRUE;
    std::string err;
    if (g_engine->load(p, err)) { g_last_error.clear(); return nullptr; }
    g_last_error = err;
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_to_eyed_inferno_sd_SdNative_unload(JNIEnv*, jclass) {
    g_engine->unload();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_to_eyed_inferno_sd_SdNative_isLoaded(JNIEnv*, jclass) {
    return g_engine->loaded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_to_eyed_inferno_sd_SdNative_cancel(JNIEnv*, jclass) {
    g_engine->cancel();
}

extern "C" JNIEXPORT jstring JNICALL
Java_to_eyed_inferno_sd_SdNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_last_error.empty() ? g_engine->last_error().c_str() : g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_to_eyed_inferno_sd_SdNative_pinBigCores(JNIEnv*, jclass, jint threads) {
    inferno_sd::pin_current_thread_to_big_cores(threads);
}

// Returns RGBA8 pixels (width*height*4) on success, null otherwise. outInfo receives
// [width, height, seed, status] with status 0 = ok, 1 = cancelled, 2 = error (see lastError()).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_to_eyed_inferno_sd_SdNative_generate(JNIEnv* env, jclass, jstring prompt, jstring negative,
                                          jint width, jint height, jint steps, jfloat cfg, jlong seed,
                                          jint sampler, jint scheduler, jboolean previews,
                                          jobject listener, jlongArray outInfo) {
    inferno_sd::GenParams p;
    p.prompt = jstring_to_utf8(env, prompt);
    p.negative_prompt = jstring_to_utf8(env, negative);
    p.width = width;
    p.height = height;
    p.steps = steps;
    p.cfg_scale = cfg;
    p.seed = seed;
    p.sampler = static_cast<inferno_sd::Sampler>(sampler);
    p.scheduler = static_cast<inferno_sd::Scheduler>(scheduler);
    p.previews = previews == JNI_TRUE;

    ListenerCtx lc{listener ? env->NewGlobalRef(listener) : nullptr};
    inferno_sd::GenCallbacks cbs;
    cbs.user = &lc;
    cbs.on_progress = &on_progress;
    cbs.on_preview = &on_preview;

    inferno_sd::GenResult res;
    std::string err;
    g_last_error.clear();
    inferno_sd::GenStatus st = g_engine->generate(p, cbs, res, err);
    if (lc.listener) env->DeleteGlobalRef(lc.listener);

    jlong info[4] = {res.width, res.height, res.seed, 0};
    jbyteArray out = nullptr;
    switch (st) {
        case inferno_sd::GenStatus::OK: {
            info[3] = 0;
            out = env->NewByteArray((jsize)res.rgba.size());
            if (out) env->SetByteArrayRegion(out, 0, (jsize)res.rgba.size(), reinterpret_cast<const jbyte*>(res.rgba.data()));
            break;
        }
        case inferno_sd::GenStatus::CANCELLED: info[3] = 1; break;
        case inferno_sd::GenStatus::ERROR: info[3] = 2; g_last_error = err; break;
    }
    if (outInfo && env->GetArrayLength(outInfo) >= 4) env->SetLongArrayRegion(outInfo, 0, 4, info);
    return out;
}
