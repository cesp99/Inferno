// Engine: lifecycle, model / projector loading, context + threadpools, sampler.
// Prompt handling / generation: inferno_engine_gen.cpp. Memory estimate: inferno_estimate.cpp.
#include "inferno_engine.h"

#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <set>

#include "ggml-backend.h"

#include "inferno_cpu.h"
#include "inferno_log.h"

namespace inferno {

namespace {

std::set<int> list_tids() {
    std::set<int> out;
    DIR * d = opendir("/proc/self/task");
    if (!d) {
        return out;
    }
    while (dirent * e = readdir(d)) {
        if (e->d_name[0] >= '0' && e->d_name[0] <= '9') {
            out.insert(atoi(e->d_name));
        }
    }
    closedir(d);
    return out;
}

void fill_cpumask(bool * mask, uint32_t bits) {
    memset(mask, 0, sizeof(bool) * GGML_MAX_N_THREADS);
    for (int i = 0; i < 32; i++) {
        if (bits & (1u << i)) {
            mask[i] = true;
        }
    }
}

// A no-op llama progress callback is not allowed to be null when user data is set; keep both together.
struct LoadProgress { Progress cb; void * ud; };

bool load_progress_cb(float p, void * u) {
    auto * c = static_cast<LoadProgress *>(u);
    if (!c->cb) {
        return true;
    }
    int done = (int) (p * 1000.0f);
    if (done < 0) done = 0;
    if (done > 1000) done = 1000;
    return c->cb(done, 1000, PHASE_LOAD, c->ud);
}

} // namespace

// ----------------------------------------------------------------------------------------------- params

ContextParams ContextParams::from_array(const int32_t * p, size_t n) {
    ContextParams c;
    if (!p || n < 12) {
        return c;
    }
    c.n_ctx           = (uint32_t) std::max<int32_t>(p[0], 256);
    c.n_batch         = (uint32_t) std::max<int32_t>(p[1], 32);
    c.n_ubatch        = (uint32_t) std::max<int32_t>(p[2], 32);
    c.n_threads       = std::max<int32_t>(p[3], 1);
    c.n_threads_batch = std::max<int32_t>(p[4], 1);
    c.big_cores_only  = p[5] != 0;
    c.flash_attn      = p[6] != 0 ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    c.type_k          = p[7];
    c.type_v          = p[8];
    c.swa_full        = p[9] != 0;
    c.poll            = (uint32_t) std::min<int32_t>(std::max<int32_t>(p[10], 0), 100);
    c.n_outputs_max   = (uint32_t) std::max<int32_t>(p[11], 1);
    if (c.n_ubatch > c.n_batch) c.n_ubatch = c.n_batch;
    return c;
}

// ----------------------------------------------------------------------------------------------- errors

Engine & Engine::get() {
    static Engine e;
    return e;
}

Engine::~Engine() {
    // process teardown; nothing to do (Kotlin frees explicitly)
}

std::string Engine::last_error() const {
    std::lock_guard<std::mutex> lock(err_mutex_);
    return err_;
}

void Engine::set_error(const std::string & msg) {
    LOGE("%s", msg.c_str());
    std::lock_guard<std::mutex> lock(err_mutex_);
    err_ = msg;
}

// ----------------------------------------------------------------------------------------------- lifecycle

void Engine::backend_init(int min_log_prio, uint32_t big_mask) {
    log_install(min_log_prio);
    big_mask_ = big_mask;
    if (big_mask_ != 0) {
        // Every thread ggml/clip spawns from the engine thread inherits this mask.
        if (!cpu_set_affinity(big_mask_)) {
            LOGW("sched_setaffinity(0x%x) failed", big_mask_);
        }
    }
    llama_backend_init();
    LOGI("%s", llama_print_system_info());
}

void Engine::backend_free() {
    model_free();
    estimate_cache_clear();
    llama_backend_free();
}

// ----------------------------------------------------------------------------------------------- model

llama_model * Engine::model_load(const std::string & path, const std::string & mmproj, bool use_mmap,
                                 int n_threads_mmproj, int image_min_tokens, int image_max_tokens,
                                 Progress cb, void * ud) {
    model_free();
    facts_ = inspect_gguf(path);
    if (!facts_.ok) {
        set_error("cannot read GGUF header: " + path);
        return nullptr;
    }

    std::vector<llama_model_tensor_buft_override> overrides;
    for (const auto & p : facts_.override_patterns) {
        overrides.push_back({ p.c_str(), ggml_backend_cpu_buffer_type() });
    }
    overrides.push_back({ nullptr, nullptr });

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers          = 0;
    mp.use_extra_bufts       = true;                  // KleidiAI / CPU_REPACK buffer types
    mp.tensor_buft_overrides = overrides.data();
    // Repacked tensors are copied out of the mapping and never read again: DIRECT_IO keeps them out of the
    // page cache. One mode applies per file, so mmap is used only when override tensors stay file-backed.
    mp.load_mode = !use_mmap ? LLAMA_LOAD_MODE_NONE
                 : facts_.override_patterns.empty() ? LLAMA_LOAD_MODE_DIRECT_IO : LLAMA_LOAD_MODE_MMAP;
    LoadProgress lp{cb, ud};
    mp.progress_callback           = load_progress_cb;
    mp.progress_callback_user_data = &lp;

    const int64_t t0 = ggml_time_ms();
    log_clear_notes();
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        set_error("failed to load model: " + path);
        facts_ = {};
        return nullptr;
    }
    model_path_ = path;
    vocab_      = llama_model_get_vocab(model_);
    arch_       = facts_.arch;
    if (const char * t = llama_model_chat_template(model_, nullptr)) {
        tmpl_ = t;
    } else {
        tmpl_.clear();
    }
    tmpl_override_.clear();
    detect_template();
    LOGI("model loaded in %lld ms (load_mode=%s, mapped=%lld MB, template=%s%s)",
         (long long) (ggml_time_ms() - t0), llama_load_mode_name(mp.load_mode),
         (long long) (facts_.mapped_bytes >> 20), tmpl_name_.c_str(), tmpl_supported_ ? "" : " [fallback]");

    // The estimate cache holds a no_alloc twin of this file; the real model supersedes it.
    for (auto it = estimates_.begin(); it != estimates_.end(); ++it) {
        if (it->path == path) {
            llama_model_free(it->model);
            estimates_.erase(it);
            break;
        }
    }

    if (!mmproj.empty()) {
        if (!mmproj_load(mmproj, n_threads_mmproj, image_min_tokens, image_max_tokens, cb, ud)) {
            model_free();
            return nullptr;
        }
    }
    return model_;
}

void Engine::model_free() {
    context_free();
    mmproj_free();
    if (model_) {
        llama_model_free(model_);
        model_ = nullptr;
    }
    vocab_ = nullptr;
    model_path_.clear();
    tmpl_.clear();
    tmpl_override_.clear();
    arch_.clear();
    facts_ = {};
}

void Engine::detect_template() {
    tmpl_gemma4_ = false;
    llama_chat_message dummy[2] = { { "user", "Hi" }, { "assistant", "Hello" } };
    char buf[512];

    if (!tmpl_override_.empty()) {
        if (tmpl_override_ == "gemma4") {
            tmpl_gemma4_ = true; tmpl_name_ = "gemma4"; tmpl_supported_ = true;
            return;
        }
        if (llama_chat_apply_template(tmpl_override_.c_str(), dummy, 2, true, buf, sizeof(buf)) >= 0) {
            tmpl_name_ = tmpl_override_; tmpl_supported_ = true;
            return;
        }
        LOGW("template override '%s' is not a built-in template; using auto-detection", tmpl_override_.c_str());
    }
    if (arch_ == "gemma4") {
        // The built-in llama_chat_apply_template rejects the Gemma 4 Jinja ("try using --jinja"); the
        // turn format is hand-rolled from the canonical template.
        tmpl_gemma4_ = true; tmpl_name_ = "gemma4"; tmpl_supported_ = true;
        return;
    }
    if (tmpl_.empty() || llama_chat_apply_template(tmpl_.c_str(), dummy, 2, true, buf, sizeof(buf)) < 0) {
        tmpl_name_ = "chatml"; tmpl_supported_ = false;
        if (!tmpl_.empty()) {
            LOGW("chat template of this model is not supported by llama_chat_apply_template; falling back to chatml");
        }
        return;
    }
    tmpl_supported_ = true;
    tmpl_name_ = "builtin";
    // Name the detected family by comparing the rendering against each built-in (cheap, ~55 tiny renders).
    const std::string ref(buf, (size_t) llama_chat_apply_template(tmpl_.c_str(), dummy, 2, true, buf, sizeof(buf)));
    const int32_t n = llama_chat_builtin_templates(nullptr, 0);
    if (n > 0) {
        std::vector<const char *> names((size_t) n);
        llama_chat_builtin_templates(names.data(), names.size());
        char b2[512];
        for (const char * name : names) {
            const int32_t r = llama_chat_apply_template(name, dummy, 2, true, b2, sizeof(b2));
            if (r >= 0 && r < (int32_t) sizeof(b2) && ref == std::string(b2, (size_t) r)) {
                tmpl_name_ = name;
                break;
            }
        }
    }
}

void Engine::model_set_template(const std::string & name) {
    tmpl_override_ = name;
    if (model_) {
        detect_template();
    }
}

bool Engine::model_info(ModelInfo & out) {
    if (!model_) {
        set_error("no model");
        return false;
    }
    out.nums[0] = (int64_t) llama_model_n_params(model_);
    out.nums[1] = (int64_t) llama_model_size(model_);
    out.nums[2] = llama_model_n_ctx_train(model_);
    out.nums[3] = llama_model_n_layer(model_);
    out.nums[4] = llama_model_n_embd(model_);
    out.nums[5] = llama_model_n_head_kv(model_);
    out.nums[6] = llama_model_n_swa(model_);
    out.nums[7] = (mctx_ && mtmd_support_vision(mctx_)) ? 1 : 0;   // only known once the projector is resident
    out.nums[8] = tmpl_supported_ ? 1 : 0;
    out.nums[9] = llama_vocab_n_tokens(vocab_);
    char buf[1024];
    out.strs[0] = arch_;
    if (llama_model_desc(model_, buf, sizeof(buf)) > 0) out.strs[1] = buf;
    std::string name;
    if (model_meta_str("general.name", name)) out.strs[2] = name;
    out.strs[3] = tmpl_name_;
    return true;
}

bool Engine::model_meta_str(const std::string & key, std::string & out) {
    if (!model_) {
        return false;
    }
    char buf[4096];
    const int32_t n = llama_model_meta_val_str(model_, key.c_str(), buf, sizeof(buf));
    if (n < 0) {
        return false;
    }
    if ((size_t) n < sizeof(buf)) {
        out.assign(buf, (size_t) n);
    } else {
        std::vector<char> big((size_t) n + 1);
        llama_model_meta_val_str(model_, key.c_str(), big.data(), big.size());
        out.assign(big.data(), (size_t) n);
    }
    return true;
}

// ----------------------------------------------------------------------------------------------- mmproj

bool Engine::clip_eval_cb(struct ggml_tensor * /*t*/, bool ask, void * ud) {
    auto * e = static_cast<Engine *>(ud);
    if (ask) {
        // Ask ggml-backend to stop after every 16th node so the encoder graph runs in short slices.
        return (e->clip_node_counter_.fetch_add(1, std::memory_order_relaxed) % 16) == 15;
    }
    return !e->cancel_.load(std::memory_order_relaxed);   // false => abort the encode
}

bool Engine::mmproj_load(const std::string & path, int n_threads, int image_min_tokens, int image_max_tokens,
                         Progress cb, void * ud) {
    if (!model_) {
        set_error("no model");
        return false;
    }
    mmproj_free();
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu           = false;
    mp.print_timings     = false;
    mp.n_threads         = std::max(n_threads, 1);
    mp.flash_attn_type   = LLAMA_FLASH_ATTN_TYPE_AUTO;
    mp.warmup            = false;      // would reserve the largest-image compute buffers for the whole session
    mp.image_min_tokens  = image_min_tokens > 0 ? image_min_tokens : -1;
    mp.image_max_tokens  = image_max_tokens > 0 ? image_max_tokens : -1;
    mp.batch_max_tokens  = ctx_ ? (int32_t) llama_n_batch(ctx_) : 1024;
    mp.cb_eval           = clip_eval_cb;
    mp.cb_eval_user_data = this;
    LoadProgress lp{cb, ud};
    mp.progress_callback           = load_progress_cb;
    mp.progress_callback_user_data = &lp;

    const int64_t t0 = ggml_time_ms();
    mctx_ = mtmd_init_from_file(path.c_str(), model_, mp);
    if (!mctx_) {
        set_error("failed to load projector: " + path);
        return false;
    }
    if (!mtmd_support_vision(mctx_)) {
        LOGW("projector has no vision encoder");
    }
    mmproj_path_ = path;
    LOGI("projector loaded in %lld ms (threads=%d)", (long long) (ggml_time_ms() - t0), mp.n_threads);
    return true;
}

void Engine::mmproj_free() {
    if (mctx_) {
        mtmd_free(mctx_);
        mctx_ = nullptr;
    }
    mmproj_path_.clear();
}

// ----------------------------------------------------------------------------------------------- context

llama_context_params Engine::context_params(const ContextParams & p) const {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = p.n_ctx;
    cp.n_batch         = p.n_batch;
    cp.n_ubatch        = p.n_ubatch;
    cp.n_seq_max       = 1;
    cp.n_outputs_max   = p.n_outputs_max;      // logits invariant: at most one output per batch
    cp.n_threads       = p.n_threads;
    cp.n_threads_batch = p.n_threads_batch;
    cp.flash_attn_type = (llama_flash_attn_type) p.flash_attn;
    cp.type_k          = (ggml_type) p.type_k;
    cp.type_v          = (ggml_type) p.type_v;
    cp.swa_full        = p.swa_full;
    cp.kv_unified      = false;
    cp.no_perf         = true;
    if (model_ && (llama_model_is_hybrid(model_) || llama_model_is_recurrent(model_))) {
        cp.n_rs_seq = 1;                       // one-token rewind without a checkpoint
    }
    return cp;
}

llama_context * Engine::context_create(const ContextParams & p) {
    if (!model_) {
        set_error("no model");
        return nullptr;
    }
    context_free();
    cparams_ = p;
    llama_context_params cp = context_params(p);
    cp.abort_callback      = &Engine::abort_cb;
    cp.abort_callback_data = this;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        set_error("failed to create context (n_ctx=" + std::to_string(p.n_ctx) + ")");
        return nullptr;
    }
    cparams_.n_ctx   = llama_n_ctx(ctx_);          // padded to 256
    cparams_.n_batch = llama_n_batch(ctx_);
    batch_ = llama_batch_init((int32_t) cparams_.n_batch, 0, 1);
    if (!build_threadpools(p.n_threads, p.n_threads_batch, p.big_cores_only, p.poll)) {
        context_free();
        return nullptr;
    }
    // Checkpoints only pay off where llama_memory_seq_rm can refuse a mid-sequence removal.
    needs_checkpoints_ = llama_model_is_hybrid(model_) || llama_model_is_recurrent(model_) ||
                         (llama_model_n_swa(model_) > 0 && !p.swa_full);
    if (!sampler_set(SamplingParams{})) {
        context_free();
        return nullptr;
    }
    kv_clear();
    LOGI("context created: n_ctx=%u n_batch=%u n_ubatch=%u fa=%d kv=%d/%d swa_full=%d checkpoints=%d",
         cparams_.n_ctx, cparams_.n_batch, p.n_ubatch, p.flash_attn, p.type_k, p.type_v, p.swa_full, needs_checkpoints_);
    return ctx_;
}

void Engine::context_free() {
    if (smpl_) {
        llama_sampler_free(smpl_);
        smpl_ = nullptr;
    }
    if (grmr_) {
        llama_sampler_free(grmr_);
        grmr_ = nullptr;
    }
    if (batch_.token) {
        llama_batch_free(batch_);
        batch_ = {};
    }
    free_threadpools();
    ckpts_.clear();
    if (ctx_) {
        llama_free(ctx_);
        ctx_ = nullptr;
    }
    cache_.clear();
    cache_media_.clear();
    n_past_ = 0;
    finish_ = Finish::eos;
}

bool Engine::build_threadpools(int n_gen, int n_batch, bool big_cores_only, uint32_t poll) {
    free_threadpools();
    n_gen   = std::max(n_gen, 1);
    n_batch = std::max(n_batch, 1);
    uint32_t mask = 0;
    if (big_cores_only) {
        mask = big_mask_ != 0 ? big_mask_ : cpu_topology().big_mask;
    }
    if (!cpu_set_affinity(mask)) {     // engine thread: pinned or unpinned together with the pools
        LOGW("sched_setaffinity(0x%x) failed", mask);
    }

    ggml_threadpool_params tpp = ggml_threadpool_params_default(n_gen);
    ggml_threadpool_params tpb = ggml_threadpool_params_default(n_batch);
    tpp.poll = tpb.poll = poll;
    tpp.prio = tpb.prio = GGML_SCHED_PRIO_NORMAL;      // SCHED_FIFO is refused for apps
    tpp.strict_cpu = tpb.strict_cpu = false;           // the mask only biases placement
    if (mask != 0) {
        fill_cpumask(tpp.cpumask, mask);
        fill_cpumask(tpb.cpumask, mask);
    }

    // Worker tids: the pool spawns its workers eagerly, so a /proc/self/task diff around the call is exact.
    const std::set<int> before = list_tids();
    tp_batch_ = ggml_threadpool_new(&tpb);
    tp_       = ggml_threadpool_params_match(&tpp, &tpb) ? tp_batch_ : ggml_threadpool_new(&tpp);
    const std::set<int> after = list_tids();
    if (!tp_ || !tp_batch_) {
        set_error("ggml_threadpool_new failed");
        return false;
    }
    worker_tids_.clear();
    worker_tids_.push_back((int) gettid());
    for (int t : after) {
        if (!before.count(t)) {
            worker_tids_.push_back(t);
        }
    }
    tp_n_ = n_gen;
    tp_batch_n_ = n_batch;
    tp_big_only_ = big_cores_only;
    tp_poll_ = poll;
    if (ctx_) {
        llama_attach_threadpool(ctx_, tp_, tp_batch_);
        llama_set_n_threads(ctx_, n_gen, n_batch);
    }
    LOGI("threadpools: gen=%d batch=%d mask=0x%x poll=%u workers=%zu", n_gen, n_batch, mask, poll, worker_tids_.size());
    return true;
}

void Engine::free_threadpools() {
    if (ctx_) {
        llama_detach_threadpool(ctx_);
    }
    if (tp_ && tp_ != tp_batch_) {
        ggml_threadpool_free(tp_);
    }
    if (tp_batch_) {
        ggml_threadpool_free(tp_batch_);
    }
    tp_ = tp_batch_ = nullptr;
    worker_tids_.clear();
}

bool Engine::set_threads(int n_gen, int n_batch, bool big_cores_only, uint32_t poll) {
    if (!ctx_) {
        set_error("no context");
        return false;
    }
    n_gen   = std::max(n_gen, 1);
    n_batch = std::max(n_batch, 1);
    // Cheap path (thermal step-down): ggml clamps the requested count to the pool size, and the mask/poll
    // of a live pool cannot change, so this only works when nothing but the counts shrink.
    if (n_gen <= tp_n_ && n_batch <= tp_batch_n_ && big_cores_only == tp_big_only_ && poll == tp_poll_) {
        llama_set_n_threads(ctx_, n_gen, n_batch);
        cparams_.n_threads = n_gen;
        cparams_.n_threads_batch = n_batch;
        return true;
    }
    cparams_.n_threads = n_gen;
    cparams_.n_threads_batch = n_batch;
    cparams_.big_cores_only = big_cores_only;
    cparams_.poll = poll;
    return build_threadpools(n_gen, n_batch, big_cores_only, poll);
}

// ----------------------------------------------------------------------------------------------- sampler

bool Engine::sampler_set(const SamplingParams & s) {
    if (!ctx_ || !vocab_) {
        set_error("no context");
        return false;
    }
    llama_sampler_chain_params cpar = llama_sampler_chain_default_params();
    cpar.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(cpar);
    // Built conditionally like common_sampler_init: a no-op sampler still walks the whole candidate
    // array (150-250k entries) per token.
    // The grammar stays outside the chain (grmr_): generate_start replays prompt tokens into smpl_ for the
    // penalty/DRY history, and a prompt token that does not fit the grammar would throw / GGML_ABORT.
    llama_sampler * g = nullptr;
    if (!s.grammar.empty()) {
        g = llama_sampler_init_grammar(vocab_, s.grammar.c_str(), "root");
        if (!g) {
            llama_sampler_free(chain);
            set_error("invalid grammar");
            return false;
        }
    }
    const bool has_penalties = s.repeat_last_n != 0 &&
        (s.repeat_penalty != 1.0f || s.freq_penalty != 0.0f || s.presence_penalty != 0.0f);
    if (has_penalties) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), s.repeat_last_n,
                                                                    s.repeat_penalty, s.freq_penalty, s.presence_penalty));
    }
    // -1 => "whole context" is a common/ convention; llama_sampler_init_dry clamps it to 0 and returns a no-op.
    const int dry_last_n = s.dry_penalty_last_n < 0 ? (int) llama_n_ctx(ctx_) : s.dry_penalty_last_n;
    if (s.dry_multiplier > 0.0f && dry_last_n != 0) {
        static const char * breakers[] = { "\n", ":", "\"", "*" };   // llama.cpp common defaults
        llama_sampler_chain_add(chain, llama_sampler_init_dry(vocab_, s.dry_multiplier, s.dry_base, s.dry_allowed_length,
                                                              dry_last_n, breakers, 4));
    }
    if (s.temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (s.top_k > 0) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(s.top_k));     // first: shrinks the set
        }
        if (s.typical_p > 0.0f && s.typical_p < 1.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_typical(s.typical_p, 1));
        }
        if (s.top_p > 0.0f && s.top_p < 1.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(s.top_p, 1));
        }
        if (s.min_p > 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(s.min_p, 1));
        }
        llama_sampler_chain_add(chain, llama_sampler_init_temp(s.temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(s.seed));
    }
    if (smpl_) {
        llama_sampler_free(smpl_);
    }
    smpl_ = chain;
    if (grmr_) {
        llama_sampler_free(grmr_);
    }
    grmr_ = g;
    return true;
}

} // namespace inferno
