#pragma once
// Engine singleton: owns the llama model / context / mtmd projector / sampler / threadpools and
// the token cache used for KV prefix reuse. No JNI types here (inferno_jni.cpp is the only glue).
//
// Threading contract: every method runs on the single Kotlin engine thread except
// cancel() and last_error(), which are callable from any thread and touch only the atomic flag /
// the mutex-guarded error string.
#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-cpu.h"      // ggml_threadpool_*
#include "mtmd.h"
#include "mtmd-helper.h"

namespace inferno {

enum Phase { PHASE_LOAD = 0, PHASE_TEXT = 1, PHASE_IMAGE = 2, PHASE_BENCH = 3 };

// Mirrors CtxP.* in NativeTypes.kt.
struct ContextParams {
    uint32_t n_ctx = 8192, n_batch = 512, n_ubatch = 512;
    int      n_threads = 4, n_threads_batch = 4;
    bool     big_cores_only = true;
    int      flash_attn = LLAMA_FLASH_ATTN_TYPE_ENABLED;   // 0 off, 1 on (Kotlin never sends -1)
    int      type_k = GGML_TYPE_F16, type_v = GGML_TYPE_F16;
    bool     swa_full = true;
    uint32_t poll = 50;
    uint32_t n_outputs_max = 8;
    static ContextParams from_array(const int32_t * p, size_t n);
};

// Mirrors SmpF.* / SmpI.* in NativeTypes.kt.
struct SamplingParams {
    float temp = 0.7f, top_p = 0.95f, min_p = 0.05f, typical_p = 1.0f;
    float repeat_penalty = 1.0f, freq_penalty = 0.0f, presence_penalty = 0.0f;
    float dry_multiplier = 0.0f, dry_base = 1.75f;
    int   top_k = 40, repeat_last_n = 64, dry_allowed_length = 2, dry_penalty_last_n = -1;
    uint32_t seed = LLAMA_DEFAULT_SEED;
    std::string grammar;
};

struct ChatMsg  { std::string role; std::string content; std::vector<std::string> image_ids; };
// rgb.empty() => placeholder bitmap (token counting only)
struct ImageRGB { std::string id; uint32_t w = 0, h = 0; std::vector<unsigned char> rgb; };

enum class Finish { running = 0, eos = 1, length = 2, context_full = 3, cancelled = 4, error = 5 };

// return false => abort the operation
using Progress = bool (*)(int done, int total, int phase, void * ud);

struct ModelInfo {                 // MInfo.* / MInfoS.*
    int64_t     nums[10] = {};
    std::string strs[4];
};
struct MemEstimate { int64_t v[8] = {}; };   // MemEst.*
struct GenStats    { double  v[8] = {}; };   // GStat.*

class Engine {
public:
    static Engine & get();

    // lifecycle ----------------------------------------------------------------
    void backend_init(int min_log_prio, uint32_t big_mask);
    void backend_free();

    // model --------------------------------------------------------------------
    llama_model * model_load(const std::string & path, const std::string & mmproj, bool use_mmap,
                             int n_threads_mmproj, int image_min_tokens, int image_max_tokens,
                             Progress cb, void * ud);
    void model_free();
    bool model_info(ModelInfo & out);
    bool model_meta_str(const std::string & key, std::string & out);
    // nullptr/empty => auto-detect; "gemma4" => hand-rolled formatter; else a llama built-in template name
    void model_set_template(const std::string & name);

    bool mmproj_load(const std::string & path, int n_threads, int image_min_tokens, int image_max_tokens,
                     Progress cb, void * ud);
    void mmproj_free();
    bool mmproj_loaded() const { return mctx_ != nullptr; }

    // context ------------------------------------------------------------------
    llama_context * context_create(const ContextParams & p);
    void     context_free();
    uint32_t n_ctx() const { return cparams_.n_ctx; }
    bool     set_threads(int n_gen, int n_batch, bool big_cores_only, uint32_t poll);
    std::vector<int> worker_tids() const { return worker_tids_; }

    bool estimate_memory(const std::string & path, const std::string & mmproj, const ContextParams & p, MemEstimate & out);
    void estimate_cache_clear();

    bool sampler_set(const SamplingParams & s);

    // tokens / templates -------------------------------------------------------
    int  token_count(const std::string & text, bool add_special, bool parse_special);
    int  prompt_token_count(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs);
    bool apply_chat_template(const std::vector<ChatMsg> & msgs, bool add_ass, std::string & out);

    // generation ---------------------------------------------------------------
    // 0 ok · 1 cancelled · 2 does not fit · -1 no ctx · -2 tokenize/template · -3 decode · -4 chunk > n_batch
    // -5 not enough memory for the encode · -6 images but no mmproj
    int  generate_start(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs, int n_predict,
                        const std::string & assistant_prefix, int64_t avail_mem, Progress cb, void * ud);
    bool generate_next(std::string & piece_out);            // false when finished
    Finish finish() const { return finish_; }
    void   stats(GenStats & out) const;
    void   cancel() { cancel_.store(true, std::memory_order_relaxed); }   // any thread

    // kv -------------------------------------------------------------------------
    void   kv_clear();
    size_t kv_used_tokens() const { return cache_.size(); }
    int    kv_n_past() const { return (int) n_past_; }

    bool bench(int n_prompt, int n_gen, int reps, Progress cb, void * ud, double out[4]);

    // errors (any thread) ---------------------------------------------------------
    std::string last_error() const;
    void set_error(const std::string & msg);

    // handle validation for the JNI layer
    bool is_model(intptr_t h) const { return h != 0 && h == (intptr_t) model_; }
    bool is_ctx  (intptr_t h) const { return h != 0 && h == (intptr_t) ctx_; }

private:
    Engine() = default;
    ~Engine();
    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    struct Media { std::string id; size_t n_tokens = 0; llama_pos n_pos = 0; };
    // Tokenised prompt: text tokens with LLAMA_TOKEN_NULL runs for images + media map (server_tokens layout).
    struct Prompt {
        std::vector<llama_token>                 tokens;
        std::map<size_t, Media>                  media;      // start idx -> media
        std::map<size_t, mtmd::input_chunk_ptr>  chunks;     // start idx -> owned image chunk copy (encode data)
        size_t                                   n_prefix = 0;   // trailing assistant-prefix tokens
    };
    // pos_min: SWA-cache minimum at capture time (iSWA); a checkpoint whose window is already incomplete is useless.
    struct Checkpoint { size_t n_tokens = 0; llama_pos pos_min = 0; llama_pos pos_max = 0; std::vector<uint8_t> data; };
    struct GgufFacts {  // read from the GGUF header only
        bool        ok = false;
        std::string arch;
        int64_t     tensor_bytes = 0;      // sum of all tensor sizes
        int64_t     mapped_bytes = 0;      // tensors matched by the buft overrides (stay file-backed)
        std::vector<std::string> override_patterns;
    };
    struct EstimateEntry {
        std::string   path;
        llama_model * model = nullptr;     // no_alloc dry model
        GgufFacts     facts;
    };
    struct MmprojEstimate { int64_t model_bytes = 0, compute_bytes = 0; };

    // engine-side helpers (inferno_engine.cpp / inferno_estimate.cpp)
    static GgufFacts inspect_gguf(const std::string & path);
    llama_context_params context_params(const ContextParams & p) const;
    bool build_threadpools(int n_gen, int n_batch, bool big_cores_only, uint32_t poll);
    void free_threadpools();
    void detect_template();
    EstimateEntry * estimate_entry(const std::string & path);
    bool mmproj_estimate(const std::string & path, MmprojEstimate & out);
    static bool abort_cb(void * ud) { return static_cast<Engine *>(ud)->cancel_.load(std::memory_order_relaxed); }
    static bool clip_eval_cb(struct ggml_tensor * t, bool ask, void * ud);

    // prompt / generation helpers (inferno_engine_gen.cpp)
    std::string render_gemma4(const std::vector<ChatMsg> & msgs, bool add_ass) const;
    bool render_prompt(const std::vector<ChatMsg> & msgs, bool add_ass, std::string & out);
    bool tokenize_text(const std::string & text, bool add_special, bool parse_special, std::vector<llama_token> & out);
    bool tokenize_prompt(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs,
                         const std::string & assistant_prefix, Prompt & out);
    size_t    common_prefix(const Prompt & p) const;
    static llama_pos pos_at(const std::map<size_t, Media> & media, size_t idx);
    llama_pos pos_next(size_t idx) const { return pos_at(cache_media_, idx); }
    bool decode_text(const llama_token * toks, size_t n, bool logits_last, Progress cb, void * ud, int done, int total);
    bool rewind_to(size_t & n_target);                 // make KV state consistent with cache_[0..n_target)
    void truncate_cache(size_t n);
    void take_checkpoint();
    void drop_checkpoints_above(size_t n_tokens);
    void resync_after_abort();

    // state --------------------------------------------------------------------
    llama_model *        model_ = nullptr;
    std::string          model_path_;
    const llama_vocab *  vocab_ = nullptr;
    mtmd_context *       mctx_  = nullptr;
    std::string          mmproj_path_;
    llama_context *      ctx_   = nullptr;
    llama_sampler *      smpl_  = nullptr;
    llama_sampler *      grmr_  = nullptr;    // grammar kept outside smpl_: only generated tokens may reach it
    std::vector<llama_token_data> cur_;       // candidate buffer for the grammar sampling path
    llama_batch          batch_ = {};
    ggml_threadpool_t    tp_ = nullptr, tp_batch_ = nullptr;
    int                  tp_n_ = 0, tp_batch_n_ = 0;
    bool                 tp_big_only_ = true;
    uint32_t             tp_poll_ = 50;
    std::vector<int>     worker_tids_;
    ContextParams        cparams_;
    uint32_t             big_mask_ = 0;       // from backend_init (0 => detect)
    bool                 needs_checkpoints_ = false;

    std::string          tmpl_;               // chat template source from GGUF (may be empty)
    std::string          tmpl_override_;      // model_set_template
    std::string          tmpl_name_ = "chatml";
    bool                 tmpl_supported_ = false;
    bool                 tmpl_gemma4_ = false;
    std::string          arch_;
    GgufFacts            facts_;

    std::vector<llama_token>  cache_;         // tokens currently in KV (seq 0)
    std::map<size_t, Media>   cache_media_;
    llama_pos                 n_past_ = 0;
    std::vector<Checkpoint>   ckpts_;         // ring of 3, oldest first

    int         n_predict_ = -1, n_gen_ = 0;
    std::string utf8_pending_;
    std::atomic<bool> cancel_{false};
    std::atomic<uint32_t> clip_node_counter_{0};
    Finish      finish_ = Finish::eos;
    int64_t     t_prefill_us_ = 0, t_decode_us_ = 0, t_image_us_ = 0;
    int         n_prompt_ = 0, n_reused_ = 0, n_full_clears_ = 0;

    std::vector<EstimateEntry>            estimates_;   // at most 2 cached no_alloc models
    std::map<std::string, MmprojEstimate> mmproj_estimates_;

    mutable std::mutex err_mutex_;
    std::string        err_;
};

} // namespace inferno
