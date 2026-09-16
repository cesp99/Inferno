// Engine: GGUF header facts and the no_alloc memory estimate (spec 4.5 step 10).
#include "inferno_engine.h"

#include <algorithm>
#include <regex>

#include "gguf.h"
#include "ggml-backend.h"
#include "llama-ext.h"      // llama_get_memory_breakdown (staging API)

#include "inferno_log.h"

namespace inferno {

namespace {
constexpr size_t MAX_ESTIMATE_ENTRIES = 2;
} // namespace

// ----------------------------------------------------------------------------------------------- gguf facts

Engine::GgufFacts Engine::inspect_gguf(const std::string & path) {
    GgufFacts f;
    gguf_init_params ip{ /*no_alloc*/ true, /*ctx*/ nullptr };
    gguf_context * g = gguf_init_from_file(path.c_str(), ip);
    if (!g) {
        return f;
    }
    const int64_t k = gguf_find_key(g, "general.architecture");
    if (k >= 0) {
        f.arch = gguf_get_val_str(g, k);
    }
    const bool has_output   = gguf_find_tensor(g, "output.weight") >= 0;
    const bool has_per_layer = gguf_find_tensor(g, "per_layer_token_embd.weight") >= 0;
    const bool has_tok_embd = gguf_find_tensor(g, "token_embd.weight") >= 0;
    // GET_ROWS-only tables gain nothing from KleidiAI/repack (MUL_MAT only) and would double the resident
    // footprint (Gemma 4 E2B per-layer embeddings); keep them file-backed on the plain CPU buffer.
    if (has_per_layer) {
        f.override_patterns.push_back("per_layer_token_embd\\.weight");
    }
    if (has_tok_embd && has_output) {          // untied embedding (tied models read it for the output head too)
        f.override_patterns.push_back("^token_embd\\.weight");
    }
    std::vector<std::regex> res;
    for (const auto & p : f.override_patterns) {
        res.emplace_back(p);
    }
    const int64_t n = gguf_get_n_tensors(g);
    for (int64_t i = 0; i < n; i++) {
        const char * name = gguf_get_tensor_name(g, i);
        const int64_t sz = (int64_t) gguf_get_tensor_size(g, i);
        f.tensor_bytes += sz;
        for (const auto & r : res) {
            if (std::regex_search(name, r)) {
                f.mapped_bytes += sz;
                break;
            }
        }
    }
    gguf_free(g);
    f.ok = true;
    return f;
}

// ----------------------------------------------------------------------------------------------- estimate

Engine::EstimateEntry * Engine::estimate_entry(const std::string & path) {
    for (auto & e : estimates_) {
        if (e.path == path) {
            return &e;
        }
    }
    EstimateEntry e;
    e.path  = path;
    e.facts = inspect_gguf(path);
    if (!e.facts.ok) {
        set_error("cannot read GGUF header: " + path);
        return nullptr;
    }
    std::vector<llama_model_tensor_buft_override> overrides;
    for (const auto & p : e.facts.override_patterns) {
        overrides.push_back({ p.c_str(), ggml_backend_cpu_buffer_type() });
    }
    overrides.push_back({ nullptr, nullptr });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers          = 0;
    mp.use_extra_bufts       = true;
    mp.tensor_buft_overrides = overrides.data();
    mp.no_alloc              = true;             // metadata + simulated allocations only
    mp.load_mode             = LLAMA_LOAD_MODE_NONE;
    const int64_t t0 = ggml_time_ms();
    e.model = llama_model_load_from_file(path.c_str(), mp);
    if (!e.model) {
        set_error("failed to read model metadata: " + path);
        return nullptr;
    }
    LOGI("estimate: dry model for %s in %lld ms", path.c_str(), (long long) (ggml_time_ms() - t0));
    if (estimates_.size() >= MAX_ESTIMATE_ENTRIES) {
        llama_model_free(estimates_.front().model);
        estimates_.erase(estimates_.begin());
    }
    estimates_.push_back(std::move(e));
    return &estimates_.back();
}

bool Engine::mmproj_estimate(const std::string & path, MmprojEstimate & out) {
    auto it = mmproj_estimates_.find(path);
    if (it != mmproj_estimates_.end()) {
        out = it->second;
        return true;
    }
    GgufFacts f = inspect_gguf(path);
    if (!f.ok) {
        set_error("cannot read GGUF header: " + path);
        return false;
    }
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu       = false;
    mp.print_timings = false;
    mp.warmup        = true;      // no_alloc: warmup only reserves the compute buffer for the largest image
    int64_t total = 0;
    for (const auto & [dev, sz] : mtmd_get_memory_usage(path.c_str(), mp)) {
        total += (int64_t) sz;
    }
    // mtmd merges weights and compute per device; the weights are known exactly from the header.
    MmprojEstimate m;
    m.model_bytes   = f.tensor_bytes;
    m.compute_bytes = std::max<int64_t>(0, total - f.tensor_bytes);
    mmproj_estimates_[path] = m;
    out = m;
    return true;
}

bool Engine::estimate_memory(const std::string & path, const std::string & mmproj, const ContextParams & p, MemEstimate & out) {
    out = {};
    EstimateEntry * e = estimate_entry(path);
    if (!e) {
        return false;
    }
    llama_context_params cp = context_params(p);
    if (llama_model_is_hybrid(e->model) || llama_model_is_recurrent(e->model)) {
        cp.n_rs_seq = 1;
    }
    llama_context * c = llama_init_from_model(e->model, cp);
    if (!c) {
        set_error("estimate: context creation failed (n_ctx=" + std::to_string(p.n_ctx) + ")");
        return false;
    }
    int64_t model = 0, kv = 0, compute = 0;
    // Mirrors common/fit.cpp: several bufts exist here (plain CPU + CPU_KLEIDIAI / CPU_REPACK extra bufts that
    // Q4_0/Q8_0 weights are repacked into). The extra bufts leave `is_host` unset, so every entry is summed:
    // this build has no non-host device.
    for (const auto & [buft, mb] : llama_get_memory_breakdown(c)) {
        if (!buft) {
            continue;
        }
        LOGD("estimate: %s model=%zu MB context=%zu MB compute=%zu MB", ggml_backend_buft_name(buft),
             mb.model >> 20, mb.context >> 20, mb.compute >> 20);
        model   += (int64_t) mb.model;
        kv      += (int64_t) mb.context;
        compute += (int64_t) mb.compute;
    }
    llama_free(c);

    int64_t mmproj_bytes = 0, clip_compute = 0;
    if (!mmproj.empty()) {
        MmprojEstimate m;
        if (!mmproj_estimate(mmproj, m)) {
            return false;
        }
        mmproj_bytes = m.model_bytes;
        clip_compute = m.compute_bytes;
    }
    size_t dev_free = 0, dev_total = 0;
    if (ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
        ggml_backend_dev_memory(dev, &dev_free, &dev_total);
    }
    const int64_t mapped = std::min<int64_t>(e->facts.mapped_bytes, model);
    out.v[0] = model;
    out.v[1] = kv;
    out.v[2] = compute;
    out.v[3] = mmproj_bytes;
    out.v[4] = (int64_t) dev_total;
    out.v[5] = model - mapped;
    out.v[6] = mapped;
    out.v[7] = clip_compute;
    return true;
}

void Engine::estimate_cache_clear() {
    for (auto & e : estimates_) {
        llama_model_free(e.model);
    }
    estimates_.clear();
    mmproj_estimates_.clear();
}

} // namespace inferno
