// JNI glue for to.eyed.inferno.engine.LlamaNative. Maps 1:1 to the Kotlin declarations; every
// piece of free-form text (model metadata, token pieces, errors) crosses as raw UTF-8 byte arrays because
// NewStringUTF only accepts modified UTF-8 (CheckJNI aborts on 4-byte code points).
#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "inferno_cpu.h"
#include "inferno_engine.h"
#include "inferno_log.h"
#include "inferno_opencl.h"

using inferno::Engine;

namespace {

jclass    g_cls_msg   = nullptr;
jfieldID  g_f_role    = nullptr, g_f_content = nullptr, g_f_image_ids = nullptr;
jclass    g_cls_img   = nullptr;
jfieldID  g_f_id      = nullptr, g_f_w = nullptr, g_f_h = nullptr, g_f_rgb = nullptr;
jmethodID g_m_progress = nullptr;

// jstring -> UTF-8 via the UTF-16 view (GetStringUTFChars would hand us modified UTF-8).
std::string jstr(JNIEnv * env, jstring s) {
    std::string out;
    if (!s) {
        return out;
    }
    const jsize n = env->GetStringLength(s);
    const jchar * u = env->GetStringChars(s, nullptr);
    if (!u) {
        return out;
    }
    out.reserve((size_t) n * 3);
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

std::string jbytes(JNIEnv * env, jbyteArray a) {
    std::string out;
    if (!a) {
        return out;
    }
    const jsize n = env->GetArrayLength(a);
    out.resize((size_t) n);
    if (n > 0) {
        env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    }
    return out;
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray((jsize) s.size());
    if (out && !s.empty()) {
        env->SetByteArrayRegion(out, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

struct JProgress { JNIEnv * env; jobject cb; };

// Called on the engine thread only, with the JNIEnv of the frame that entered native.
bool jprogress(int done, int total, int phase, void * ud) {
    auto * p = static_cast<JProgress *>(ud);
    if (!p || !p->cb) {
        return true;
    }
    const jboolean r = p->env->CallBooleanMethod(p->cb, g_m_progress, (jint) done, (jint) total, (jint) phase);
    if (p->env->ExceptionCheck()) {
        p->env->ExceptionClear();
        return false;
    }
    return r == JNI_TRUE;
}

bool read_messages(JNIEnv * env, jobjectArray msgs, std::vector<inferno::ChatMsg> & out) {
    const jsize n = msgs ? env->GetArrayLength(msgs) : 0;
    out.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        jobject o = env->GetObjectArrayElement(msgs, i);
        if (!o) {
            return false;
        }
        inferno::ChatMsg m;
        auto role = (jstring) env->GetObjectField(o, g_f_role);
        m.role = jstr(env, role);
        env->DeleteLocalRef(role);
        auto content = (jbyteArray) env->GetObjectField(o, g_f_content);
        m.content = jbytes(env, content);
        env->DeleteLocalRef(content);
        auto ids = (jobjectArray) env->GetObjectField(o, g_f_image_ids);
        const jsize ni = ids ? env->GetArrayLength(ids) : 0;
        for (jsize k = 0; k < ni; k++) {
            auto id = (jstring) env->GetObjectArrayElement(ids, k);
            m.image_ids.push_back(jstr(env, id));
            env->DeleteLocalRef(id);
        }
        if (ids) env->DeleteLocalRef(ids);
        env->DeleteLocalRef(o);
        out.push_back(std::move(m));
    }
    return true;
}

bool read_images(JNIEnv * env, jobjectArray images, std::vector<inferno::ImageRGB> & out) {
    const jsize n = images ? env->GetArrayLength(images) : 0;
    out.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        jobject o = env->GetObjectArrayElement(images, i);
        if (!o) {
            return false;
        }
        inferno::ImageRGB r;
        auto id = (jstring) env->GetObjectField(o, g_f_id);
        r.id = jstr(env, id);
        env->DeleteLocalRef(id);
        r.w = (uint32_t) env->GetIntField(o, g_f_w);
        r.h = (uint32_t) env->GetIntField(o, g_f_h);
        auto arr = (jbyteArray) env->GetObjectField(o, g_f_rgb);
        if (arr) {
            const jsize nb = env->GetArrayLength(arr);
            r.rgb.resize((size_t) nb);
            if (nb > 0) {
                env->GetByteArrayRegion(arr, 0, nb, reinterpret_cast<jbyte *>(r.rgb.data()));
            }
            env->DeleteLocalRef(arr);
        }
        env->DeleteLocalRef(o);
        out.push_back(std::move(r));
    }
    return true;
}

bool check_model(jlong h) {
    if (!Engine::get().is_model((intptr_t) h)) {
        Engine::get().set_error("stale handle (model)");
        return false;
    }
    return true;
}

bool check_ctx(jlong h) {
    if (!Engine::get().is_ctx((intptr_t) h)) {
        Engine::get().set_error("stale handle (context)");
        return false;
    }
    return true;
}

} // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_to_eyed_inferno_engine_LlamaNative_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass msg = env->FindClass("to/eyed/inferno/engine/NativeChatMessage");
    jclass img = env->FindClass("to/eyed/inferno/engine/NativeImage");
    jclass cb  = env->FindClass("to/eyed/inferno/engine/ProgressCallback");
    if (!msg || !img || !cb) {
        return JNI_ERR;
    }
    g_cls_msg     = (jclass) env->NewGlobalRef(msg);
    g_f_role      = env->GetFieldID(g_cls_msg, "role", "Ljava/lang/String;");
    g_f_content   = env->GetFieldID(g_cls_msg, "content", "[B");
    g_f_image_ids = env->GetFieldID(g_cls_msg, "imageIds", "[Ljava/lang/String;");
    g_cls_img     = (jclass) env->NewGlobalRef(img);
    g_f_id        = env->GetFieldID(g_cls_img, "id", "Ljava/lang/String;");
    g_f_w         = env->GetFieldID(g_cls_img, "width", "I");
    g_f_h         = env->GetFieldID(g_cls_img, "height", "I");
    g_f_rgb       = env->GetFieldID(g_cls_img, "rgb", "[B");
    g_m_progress  = env->GetMethodID(cb, "onProgress", "(III)Z");
    if (!g_f_role || !g_f_content || !g_f_image_ids || !g_f_id || !g_f_w || !g_f_h || !g_f_rgb || !g_m_progress) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

// ----------------------------------------------------------------------------------------------- lifecycle

JNI_FN(void, backendInit)(JNIEnv * env, jclass, jint min_log_prio, jint big_mask, jint gpu_policy, jstring cache_dir) {
    Engine::get().backend_init((int) min_log_prio, (uint32_t) big_mask, (int) gpu_policy, jstr(env, cache_dir));
}

JNI_FN(void, setGpuPolicy)(JNIEnv *, jclass, jint gpu_policy) {
    Engine::get().set_gpu_policy((int) gpu_policy);
}

// "name\tversion\tdriver\tadreno(0/1)\tusable(0/1)\tactive(0/1)", empty when no OpenCL GPU answered. ASCII from the driver.
JNI_FN(jstring, gpuInfo)(JNIEnv * env, jclass) {
    const inferno::GpuProbe & g = inferno::opencl_probe();
    std::string out;
    if (g.available) {
        out = g.name + "\t" + g.version + "\t" + g.driver + (g.adreno ? "\t1" : "\t0")
            + (Engine::get().gpu_device() ? "\t1" : "\t0") + (Engine::get().gpu_active(false) ? "\t1" : "\t0");
    }
    return env->NewStringUTF(out.c_str());
}

JNI_FN(void, backendFree)(JNIEnv *, jclass) {
    Engine::get().backend_free();
}

JNI_FN(void, setLogPriority)(JNIEnv *, jclass, jint min_log_prio) {
    inferno::log_set_min_priority((int) min_log_prio);
}

JNI_FN(jstring, modelBufferTypes)(JNIEnv * env, jclass) {
    return env->NewStringUTF(inferno::log_notes().c_str());   // ASCII log lines from llama.cpp
}

JNI_FN(jstring, systemInfo)(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());     // pure ASCII from ggml; the only NewStringUTF here
}

JNI_FN(jintArray, cpuTopology)(JNIEnv * env, jclass) {
    const inferno::CpuTopology t = inferno::cpu_topology();
    jint v[7] = { t.n_cores, t.n_big, (jint) t.big_mask, t.has_dotprod, t.has_fp16, t.has_i8mm, t.has_sve };
    jintArray out = env->NewIntArray(7);
    if (out) env->SetIntArrayRegion(out, 0, 7, v);
    return out;
}

// ----------------------------------------------------------------------------------------------- model

JNI_FN(jlong, modelLoad)(JNIEnv * env, jclass, jstring path, jstring mmproj, jboolean use_mmap, jint n_threads_mmproj,
                         jint image_min_tokens, jint image_max_tokens, jobject progress) {
    JProgress jp{env, progress};
    llama_model * m = Engine::get().model_load(jstr(env, path), jstr(env, mmproj), use_mmap == JNI_TRUE,
                                               (int) n_threads_mmproj, (int) image_min_tokens, (int) image_max_tokens,
                                               progress ? &jprogress : nullptr, &jp);
    return (jlong) (intptr_t) m;
}

JNI_FN(void, modelFree)(JNIEnv *, jclass, jlong model) {
    if (model != 0 && Engine::get().is_model((intptr_t) model)) {
        Engine::get().model_free();
    }
}

JNI_FN(jlongArray, modelInfoNumbers)(JNIEnv * env, jclass, jlong model) {
    inferno::ModelInfo info;
    if (!check_model(model) || !Engine::get().model_info(info)) {
        return nullptr;
    }
    jlongArray out = env->NewLongArray(11);
    if (out) env->SetLongArrayRegion(out, 0, 11, reinterpret_cast<const jlong *>(info.nums));
    return out;
}

JNI_FN(jobjectArray, modelInfoStrings)(JNIEnv * env, jclass, jlong model) {
    inferno::ModelInfo info;
    if (!check_model(model) || !Engine::get().model_info(info)) {
        return nullptr;
    }
    jclass bytes_cls = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(4, bytes_cls, nullptr);
    if (!out) {
        return nullptr;
    }
    for (int i = 0; i < 4; i++) {
        jbyteArray b = to_jbytes(env, info.strs[i]);
        env->SetObjectArrayElement(out, i, b);
        env->DeleteLocalRef(b);
    }
    return out;
}

JNI_FN(jbyteArray, modelMetaStr)(JNIEnv * env, jclass, jlong model, jstring key) {
    std::string v;
    if (!check_model(model) || !Engine::get().model_meta_str(jstr(env, key), v)) {
        return nullptr;
    }
    return to_jbytes(env, v);
}

JNI_FN(void, modelSetTemplate)(JNIEnv * env, jclass, jlong model, jstring name) {
    if (check_model(model)) {
        Engine::get().model_set_template(jstr(env, name));
    }
}

JNI_FN(jboolean, mmprojLoad)(JNIEnv * env, jclass, jlong model, jstring path, jint n_threads, jint image_min_tokens,
                             jint image_max_tokens, jobject progress) {
    if (!check_model(model)) {
        return JNI_FALSE;
    }
    JProgress jp{env, progress};
    return Engine::get().mmproj_load(jstr(env, path), (int) n_threads, (int) image_min_tokens, (int) image_max_tokens,
                                     progress ? &jprogress : nullptr, &jp) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, mmprojFree)(JNIEnv *, jclass, jlong model) {
    if (model != 0 && Engine::get().is_model((intptr_t) model)) {
        Engine::get().mmproj_free();
    }
}

JNI_FN(jboolean, mmprojLoaded)(JNIEnv *, jclass, jlong model) {
    return (model != 0 && Engine::get().is_model((intptr_t) model) && Engine::get().mmproj_loaded()) ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------------------------------------------------------------- context

JNI_FN(jlong, contextCreate)(JNIEnv * env, jclass, jlong model, jintArray params) {
    if (!check_model(model)) {
        return 0;
    }
    std::vector<jint> p;
    if (params) {
        p.resize((size_t) env->GetArrayLength(params));
        if (!p.empty()) env->GetIntArrayRegion(params, 0, (jsize) p.size(), p.data());
    }
    if (p.size() < 12) {
        Engine::get().set_error("contextCreate: params.size < CtxP.SIZE");
        return 0;
    }
    const inferno::ContextParams cp = inferno::ContextParams::from_array(p.data(), p.size());
    return (jlong) (intptr_t) Engine::get().context_create(cp);
}

JNI_FN(void, contextFree)(JNIEnv *, jclass, jlong ctx) {
    if (ctx != 0 && Engine::get().is_ctx((intptr_t) ctx)) {
        Engine::get().context_free();
    }
}

JNI_FN(jint, contextNCtx)(JNIEnv *, jclass, jlong ctx) {
    return check_ctx(ctx) ? (jint) Engine::get().n_ctx() : 0;
}

JNI_FN(jboolean, contextSetThreads)(JNIEnv *, jclass, jlong ctx, jint n_gen, jint n_batch, jboolean big_cores_only, jint poll) {
    if (!check_ctx(ctx)) {
        return JNI_FALSE;
    }
    return Engine::get().set_threads((int) n_gen, (int) n_batch, big_cores_only == JNI_TRUE, (uint32_t) (poll < 0 ? 0 : poll)) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jintArray, contextWorkerTids)(JNIEnv * env, jclass, jlong ctx) {
    std::vector<int> tids;
    if (check_ctx(ctx)) {
        tids = Engine::get().worker_tids();
    }
    jintArray out = env->NewIntArray((jsize) tids.size());
    if (out && !tids.empty()) env->SetIntArrayRegion(out, 0, (jsize) tids.size(), reinterpret_cast<const jint *>(tids.data()));
    return out;
}

JNI_FN(jlongArray, estimateMemory)(JNIEnv * env, jclass, jstring path, jstring mmproj, jintArray params) {
    std::vector<jint> p;
    if (params) {
        p.resize((size_t) env->GetArrayLength(params));
        if (!p.empty()) env->GetIntArrayRegion(params, 0, (jsize) p.size(), p.data());
    }
    if (p.size() < 12) {
        Engine::get().set_error("estimateMemory: params.size < CtxP.SIZE");
        return env->NewLongArray(0);
    }
    inferno::MemEstimate est;
    if (!Engine::get().estimate_memory(jstr(env, path), jstr(env, mmproj), inferno::ContextParams::from_array(p.data(), p.size()), est)) {
        return env->NewLongArray(0);     // contract: non-null; empty == error (lastError)
    }
    jlongArray out = env->NewLongArray(8);
    if (out) env->SetLongArrayRegion(out, 0, 8, reinterpret_cast<const jlong *>(est.v));
    return out;
}

JNI_FN(void, estimateCacheClear)(JNIEnv *, jclass) {
    Engine::get().estimate_cache_clear();
}

// ----------------------------------------------------------------------------------------------- sampling

JNI_FN(jboolean, samplerSet)(JNIEnv * env, jclass, jlong ctx, jfloatArray f, jintArray i, jstring grammar) {
    if (!check_ctx(ctx)) {
        return JNI_FALSE;
    }
    std::vector<jfloat> fv;
    std::vector<jint> iv;
    if (f) { fv.resize((size_t) env->GetArrayLength(f)); if (!fv.empty()) env->GetFloatArrayRegion(f, 0, (jsize) fv.size(), fv.data()); }
    if (i) { iv.resize((size_t) env->GetArrayLength(i)); if (!iv.empty()) env->GetIntArrayRegion(i, 0, (jsize) iv.size(), iv.data()); }
    if (fv.size() < 9 || iv.size() < 5) {
        Engine::get().set_error("samplerSet: array sizes < SmpF.SIZE / SmpI.SIZE");
        return JNI_FALSE;
    }
    inferno::SamplingParams s;
    s.temp = fv[0]; s.top_p = fv[1]; s.min_p = fv[2]; s.typical_p = fv[3];
    s.repeat_penalty = fv[4]; s.freq_penalty = fv[5]; s.presence_penalty = fv[6];
    s.dry_multiplier = fv[7]; s.dry_base = fv[8];
    s.top_k = iv[0]; s.repeat_last_n = iv[1]; s.dry_allowed_length = iv[2]; s.dry_penalty_last_n = iv[3];
    s.seed = iv[4] < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) iv[4];
    s.grammar = jstr(env, grammar);
    return Engine::get().sampler_set(s) ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------------------------------------------------------------- tokens / templates

JNI_FN(jint, tokenCount)(JNIEnv * env, jclass, jlong model, jbyteArray text, jboolean add_special, jboolean parse_special) {
    if (!check_model(model)) {
        return -1;
    }
    return (jint) Engine::get().token_count(jbytes(env, text), add_special == JNI_TRUE, parse_special == JNI_TRUE);
}

JNI_FN(jint, promptTokenCount)(JNIEnv * env, jclass, jlong ctx, jobjectArray msgs, jobjectArray images) {
    if (!check_ctx(ctx)) {
        return -1;
    }
    std::vector<inferno::ChatMsg> cm;
    std::vector<inferno::ImageRGB> im;
    if (!read_messages(env, msgs, cm) || !read_images(env, images, im)) {
        Engine::get().set_error("promptTokenCount: null element");
        return -1;
    }
    return (jint) Engine::get().prompt_token_count(cm, im);
}

JNI_FN(jbyteArray, applyChatTemplate)(JNIEnv * env, jclass, jlong model, jobjectArray msgs, jboolean add_assistant) {
    if (!check_model(model)) {
        return nullptr;
    }
    std::vector<inferno::ChatMsg> cm;
    if (!read_messages(env, msgs, cm)) {
        Engine::get().set_error("applyChatTemplate: null element");
        return nullptr;
    }
    std::string out;
    if (!Engine::get().apply_chat_template(cm, add_assistant == JNI_TRUE, out)) {
        return nullptr;
    }
    return to_jbytes(env, out);
}

// ----------------------------------------------------------------------------------------------- generation

JNI_FN(jint, generateStart)(JNIEnv * env, jclass, jlong ctx, jobjectArray msgs, jobjectArray images, jint n_predict,
                            jbyteArray assistant_prefix, jlong avail_mem, jobject progress) {
    if (!check_ctx(ctx)) {
        return -1;
    }
    std::vector<inferno::ChatMsg> cm;
    std::vector<inferno::ImageRGB> im;
    if (!read_messages(env, msgs, cm) || !read_images(env, images, im)) {
        Engine::get().set_error("generateStart: null element");
        return -2;
    }
    JProgress jp{env, progress};
    return (jint) Engine::get().generate_start(cm, im, (int) n_predict, jbytes(env, assistant_prefix), (int64_t) avail_mem,
                                               progress ? &jprogress : nullptr, &jp);
}

JNI_FN(jbyteArray, generateNext)(JNIEnv * env, jclass, jlong ctx) {
    if (!check_ctx(ctx)) {
        return nullptr;
    }
    std::string piece;
    if (!Engine::get().generate_next(piece)) {
        return nullptr;
    }
    return to_jbytes(env, piece);
}

JNI_FN(jint, generateFinishReason)(JNIEnv *, jclass, jlong ctx) {
    if (!check_ctx(ctx)) {
        return (jint) inferno::Finish::error;
    }
    return (jint) Engine::get().finish();
}

JNI_FN(jdoubleArray, generateStats)(JNIEnv * env, jclass, jlong ctx) {
    inferno::GenStats st;
    if (check_ctx(ctx)) {
        Engine::get().stats(st);
    }
    jdoubleArray out = env->NewDoubleArray(8);
    if (out) env->SetDoubleArrayRegion(out, 0, 8, st.v);
    return out;
}

JNI_FN(void, cancel)(JNIEnv *, jclass) {
    Engine::get().cancel();      // any thread: atomic flag only, no handle, no pointer
}

// ----------------------------------------------------------------------------------------------- kv

JNI_FN(void, kvClear)(JNIEnv *, jclass, jlong ctx) {
    if (check_ctx(ctx)) {
        Engine::get().kv_clear();
    }
}

JNI_FN(jint, kvUsedTokens)(JNIEnv *, jclass, jlong ctx) {
    return check_ctx(ctx) ? (jint) Engine::get().kv_used_tokens() : 0;
}

JNI_FN(jint, kvNPast)(JNIEnv *, jclass, jlong ctx) {
    return check_ctx(ctx) ? (jint) Engine::get().kv_n_past() : 0;
}

// ----------------------------------------------------------------------------------------------- bench

JNI_FN(jdoubleArray, bench)(JNIEnv * env, jclass, jlong ctx, jint n_prompt, jint n_gen, jint reps, jobject progress) {
    if (!check_ctx(ctx)) {
        return env->NewDoubleArray(0);
    }
    JProgress jp{env, progress};
    double r[4] = { 0, 0, 0, 0 };
    if (!Engine::get().bench((int) n_prompt, (int) n_gen, (int) reps, progress ? &jprogress : nullptr, &jp, r)) {
        return env->NewDoubleArray(0);   // contract: non-null; empty == error / cancelled (lastError)
    }
    jdoubleArray out = env->NewDoubleArray(4);
    if (out) env->SetDoubleArrayRegion(out, 0, 4, r);
    return out;
}

JNI_FN(jbyteArray, lastError)(JNIEnv * env, jclass) {
    return to_jbytes(env, Engine::get().last_error());
}
