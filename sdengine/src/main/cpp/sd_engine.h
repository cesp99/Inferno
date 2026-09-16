// Image-generation engine wrapper around stable-diffusion.cpp (one sd_ctx at a time).
//
// Threading model: every method except cancel() must be called from the single worker
// thread owned by the Kotlin ImageEngine. generate() blocks on that thread for the whole
// run and fires the callbacks from it (sd.cpp samples synchronously on the caller thread).
// cancel() may be called from any thread.
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"  // sd_image_t is a typedef of an anonymous struct: cannot be forward-declared

namespace inferno_sd {

// Stable integer codes shared with SdNative.kt; mapped to sd.cpp enums in sd_engine.cpp so the
// Kotlin side never depends on stable-diffusion.h enum ordinals.
enum class Sampler : int { EULER = 0, EULER_A = 1, LCM = 2 };
enum class Scheduler : int { DISCRETE = 0, SGM_UNIFORM = 1 };

struct LoadParams {
    std::string model_path;
    std::string taesd_path;
    int n_threads = 4;
    bool diffusion_flash_attn = true;
};

struct GenParams {
    std::string prompt;
    std::string negative_prompt;
    int width = 512;
    int height = 512;
    int steps = 4;
    float cfg_scale = 1.0f;
    int64_t seed = 42;
    Sampler sampler = Sampler::LCM;
    Scheduler scheduler = Scheduler::DISCRETE;
    bool previews = false;
};

struct GenCallbacks {
    void* user = nullptr;
    // Sampling progress only (loader / tiling progress is filtered out). step 0 == "started".
    void (*on_progress)(void* user, int step, int steps, float sec_per_step) = nullptr;
    // Latent-projection preview at latent resolution (width/8 x height/8), RGBA8.
    void (*on_preview)(void* user, int step, int width, int height, const uint8_t* rgba) = nullptr;
};

struct GenResult {
    int width = 0;
    int height = 0;
    int64_t seed = 0;
    std::vector<uint8_t> rgba;
};

enum class GenStatus { OK, CANCELLED, ERROR };

// Pins the calling thread to the n fastest cores (by cpuinfo_max_freq). ggml spawns its
// disposable thread pool from the calling thread with an all-zero cpumask, so the pool
// inherits this mask: this is the taskset-equivalent used for the benchmark numbers.
void pin_current_thread_to_big_cores(int n_threads);

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool load(const LoadParams& params, std::string& error);
    void unload();
    bool loaded() const { return ctx_ != nullptr; }

    GenStatus generate(const GenParams& params, const GenCallbacks& cbs, GenResult& out, std::string& error);

    // Thread-safe. Sets the sd.cpp cancel flag (checked once per denoise step) and the
    // graph-level abort flag (checked every kCancelCheckNodes graph nodes, i.e. well under
    // a second even on a phone), so the running generate() returns CANCELLED promptly.
    void cancel();

    std::string last_error() const;

private:
    static void log_cb(enum sd_log_level_t level, const char* text, void* data);
    static void progress_cb(int step, int steps, float time, void* data);
    static void preview_cb(int step, int frame_count, sd_image_t* frames, bool is_noisy, void* data);
    static bool eval_cb(ggml_tensor* t, bool ask, void* data);

    static constexpr int kCancelCheckNodes = 32;

    sd_ctx_t* ctx_ = nullptr;
    mutable std::mutex ctx_mu_;   // guards ctx_ against cancel() racing unload()
    mutable std::mutex err_mu_;
    std::string last_error_;

    std::atomic<bool> cancel_{false};
    std::atomic<bool> generating_{false};
    std::atomic<uint32_t> node_counter_{0};
    int n_threads_ = 4;
    int requested_steps_ = 0;
    const GenCallbacks* cbs_ = nullptr;
};

}  // namespace inferno_sd
