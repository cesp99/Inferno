#include "sd_engine.h"

#include <android/log.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>

#include "stable-diffusion.h"

#define TAG "inferno_sd"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace inferno_sd {

// ---------------------------------------------------------------------------------------------
// CPU affinity
// ---------------------------------------------------------------------------------------------

static long read_long_file(const char* path, long fallback) {
    FILE* f = fopen(path, "r");
    if (!f) return fallback;
    long v = fallback;
    if (fscanf(f, "%ld", &v) != 1) v = fallback;
    fclose(f);
    return v;
}

void pin_current_thread_to_big_cores(int n_threads) {
    long n_cpu = sysconf(_SC_NPROCESSORS_CONF);
    if (n_cpu <= 0 || n_cpu > CPU_SETSIZE) return;
    if (n_threads <= 0 || n_threads >= n_cpu) return;  // nothing to gain from pinning

    struct Core { int id; long max_khz; };
    std::vector<Core> cores;
    cores.reserve((size_t)n_cpu);
    for (int i = 0; i < n_cpu; ++i) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        cores.push_back({i, read_long_file(path, 0)});
    }
    // Highest clock first; stable so equal cores keep ascending ids (big cluster is cpu4-7 on the Seeker).
    std::stable_sort(cores.begin(), cores.end(), [](const Core& a, const Core& b) { return a.max_khz > b.max_khz; });
    if (cores.front().max_khz == cores.back().max_khz) return;  // homogeneous SoC / emulator: leave the scheduler alone

    // Take the n fastest cores, but never split a cluster: if the n-th core's clock equals the
    // (n+1)-th we include the whole tier so ggml's static partitioning is not starved by one core.
    size_t take = (size_t)n_threads;
    while (take < cores.size() && cores[take].max_khz == cores[take - 1].max_khz) ++take;

    cpu_set_t set;
    CPU_ZERO(&set);
    for (size_t i = 0; i < take; ++i) CPU_SET(cores[i].id, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        ALOGW("sched_setaffinity failed: %s", strerror(errno));
    }
}

// ---------------------------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------------------------

Engine::Engine() {
    // sd.cpp callbacks are process-global; there is exactly one Engine per process.
    sd_set_log_callback(&Engine::log_cb, this);
    sd_set_progress_callback(&Engine::progress_cb, this);
}

Engine::~Engine() {
    unload();
    sd_set_backend_eval_callback(nullptr, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
    sd_set_progress_callback(nullptr, nullptr);
    sd_set_log_callback(nullptr, nullptr);
}

std::string Engine::last_error() const {
    std::lock_guard<std::mutex> g(err_mu_);
    return last_error_;
}

void Engine::log_cb(enum sd_log_level_t level, const char* text, void* data) {
    if (!text) return;
    auto* self = static_cast<Engine*>(data);
    int prio;
    switch (level) {
        case SD_LOG_DEBUG: prio = ANDROID_LOG_VERBOSE; break;
        case SD_LOG_VERBOSE: prio = ANDROID_LOG_DEBUG; break;
        case SD_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case SD_LOG_WARN: prio = ANDROID_LOG_WARN; break;
        default: prio = ANDROID_LOG_ERROR; break;
    }
    // sd.cpp lines end with '\n'; logcat adds its own.
    size_t len = strlen(text);
    while (len > 0 && (text[len - 1] == '\n' || text[len - 1] == '\r')) --len;
    __android_log_print(prio, TAG, "%.*s", (int)len, text);
    if (level == SD_LOG_ERROR && self) {
        // Keep the most recent error so a failed new_sd_ctx/generate_image can surface a real reason.
        std::lock_guard<std::mutex> g(self->err_mu_);
        self->last_error_.assign(text, len);
    }
}

void Engine::progress_cb(int step, int steps, float time, void* data) {
    auto* self = static_cast<Engine*>(data);
    if (!self || !self->generating_.load(std::memory_order_acquire)) return;
    // The same hook also reports tensor-loading counts (lazy weight loads inside generate_image)
    // and VAE tile counts; only the sampling loop reports exactly the requested step count.
    if (steps != self->requested_steps_) return;
    const GenCallbacks* cbs = self->cbs_;
    if (cbs && cbs->on_progress) cbs->on_progress(cbs->user, step, steps, time);
}

void Engine::preview_cb(int step, int frame_count, sd_image_t* frames, bool /*is_noisy*/, void* data) {
    auto* self = static_cast<Engine*>(data);
    if (!self || !self->generating_.load(std::memory_order_acquire)) return;
    const GenCallbacks* cbs = self->cbs_;
    if (!cbs || !cbs->on_preview || frame_count < 1 || !frames || !frames[0].data) return;
    const sd_image_t& f = frames[0];
    if (f.channel != 3 && f.channel != 4) return;
    std::vector<uint8_t> rgba((size_t)f.width * f.height * 4);
    const size_t n = (size_t)f.width * f.height;
    for (size_t i = 0; i < n; ++i) {
        rgba[i * 4 + 0] = f.data[i * f.channel + 0];
        rgba[i * 4 + 1] = f.data[i * f.channel + 1];
        rgba[i * 4 + 2] = f.data[i * f.channel + 2];
        rgba[i * 4 + 3] = 255;
    }
    cbs->on_preview(cbs->user, step, (int)f.width, (int)f.height, rgba.data());
}

// ggml evaluates the graph in ranges delimited by nodes for which we answer "yes" to `ask`;
// after each such range it calls back with ask=false and a `false` answer aborts the graph
// (GGML_STATUS_ABORTED -> generate_image returns false). Every 32 nodes is a few hundred ms
// on the Seeker, which is the cancel latency this buys us; the per-range cost is one extra
// thread-pool spin-up, negligible against 13 s UNet steps.
bool Engine::eval_cb(ggml_tensor* /*t*/, bool ask, void* data) {
    auto* self = static_cast<Engine*>(data);
    if (!self) return true;
    if (ask) {
        uint32_t n = self->node_counter_.fetch_add(1, std::memory_order_relaxed) + 1;
        return (n % kCancelCheckNodes) == 0;
    }
    return !self->cancel_.load(std::memory_order_acquire);
}

bool Engine::load(const LoadParams& params, std::string& error) {
    unload();
    n_threads_ = params.n_threads;
    pin_current_thread_to_big_cores(n_threads_);
    {
        std::lock_guard<std::mutex> g(err_mu_);
        last_error_.clear();
    }

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = params.model_path.c_str();
    p.taesd_path = params.taesd_path.empty() ? nullptr : params.taesd_path.c_str();
    p.n_threads = params.n_threads;
    p.wtype = SD_TYPE_COUNT;              // never re-quantize on the phone: use the GGUF as-is
    p.rng_type = CUDA_RNG;                // seed-compatible with sd-cli / the benchmark outputs
    p.diffusion_flash_attn = params.diffusion_flash_attn;  // shrinks the UNet compute buffer
    p.enable_mmap = true;                 // weights live in page cache; MemAvailable stays high
    p.eager_load = false;                 // lazy: TE, UNet and TAE are loaded when first used

    ALOGI("loading model=%s taesd=%s threads=%d fa=%d", params.model_path.c_str(),
          params.taesd_path.c_str(), params.n_threads, (int)params.diffusion_flash_attn);
    sd_ctx_t* ctx = new_sd_ctx(&p);
    if (!ctx) {
        error = last_error();
        if (error.empty()) error = "new_sd_ctx failed";
        return false;
    }
    if (!sd_ctx_supports_image_generation(ctx)) {
        free_sd_ctx(ctx);
        error = "model does not support image generation";
        return false;
    }
    {
        std::lock_guard<std::mutex> g(ctx_mu_);
        ctx_ = ctx;
    }
    ALOGI("loaded: %s", sd_get_model_version_name(ctx));
    return true;
}

void Engine::unload() {
    sd_ctx_t* ctx;
    {
        std::lock_guard<std::mutex> g(ctx_mu_);
        ctx = ctx_;
        ctx_ = nullptr;
    }
    if (ctx) {
        ALOGI("unloading");
        free_sd_ctx(ctx);
    }
}

void Engine::cancel() {
    cancel_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> g(ctx_mu_);
    if (ctx_) sd_cancel_generation(ctx_, SD_CANCEL_ALL);
}

static enum sample_method_t to_sd(Sampler s) {
    switch (s) {
        case Sampler::EULER: return EULER_SAMPLE_METHOD;
        case Sampler::EULER_A: return EULER_A_SAMPLE_METHOD;
        case Sampler::LCM: return LCM_SAMPLE_METHOD;
    }
    return EULER_SAMPLE_METHOD;
}

static enum scheduler_t to_sd(Scheduler s) {
    switch (s) {
        case Scheduler::DISCRETE: return DISCRETE_SCHEDULER;
        case Scheduler::SGM_UNIFORM: return SGM_UNIFORM_SCHEDULER;
    }
    return DISCRETE_SCHEDULER;
}

GenStatus Engine::generate(const GenParams& params, const GenCallbacks& cbs, GenResult& out, std::string& error) {
    if (!ctx_) {
        error = "no model loaded";
        return GenStatus::ERROR;
    }
    pin_current_thread_to_big_cores(n_threads_);  // generate may run on a fresh thread

    sd_img_gen_params_t p;
    sd_img_gen_params_init(&p);
    p.prompt = params.prompt.c_str();
    p.negative_prompt = params.negative_prompt.c_str();
    p.width = params.width;
    p.height = params.height;
    p.seed = params.seed;
    p.batch_count = 1;
    p.clip_skip = -1;
    p.sample_params.sample_method = to_sd(params.sampler);
    p.sample_params.scheduler = to_sd(params.scheduler);
    p.sample_params.sample_steps = params.steps;
    p.sample_params.guidance.txt_cfg = params.cfg_scale;  // 1.0 skips the unconditional pass
    // TAESD at 512 px needs no tiling; above that its compute buffer passes 1 GB (measured), so tile.
    p.vae_tiling_params.enabled = (int64_t)params.width * params.height > 512 * 512;

    {
        std::lock_guard<std::mutex> g(err_mu_);
        last_error_.clear();
    }
    cancel_.store(false, std::memory_order_release);
    node_counter_.store(0, std::memory_order_relaxed);
    requested_steps_ = params.steps;
    cbs_ = &cbs;
    sd_set_backend_eval_callback(&Engine::eval_cb, this);
    if (params.previews && cbs.on_preview) {
        // PROJ = latent->RGB matrix at latent resolution: free, unlike a TAE decode per step.
        sd_set_preview_callback(&Engine::preview_cb, PREVIEW_PROJ, 1, true, false, this);
    } else {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
    }
    generating_.store(true, std::memory_order_release);

    sd_image_t* images = nullptr;
    int n_images = 0;
    bool ok = generate_image(ctx_, &p, &images, &n_images);

    generating_.store(false, std::memory_order_release);
    sd_set_backend_eval_callback(nullptr, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
    cbs_ = nullptr;

    if (!ok || n_images < 1 || !images || !images[0].data) {
        if (images) free_sd_images(images, n_images);
        if (cancel_.load(std::memory_order_acquire)) return GenStatus::CANCELLED;
        error = last_error();
        if (error.empty()) error = "generate_image failed";
        return GenStatus::ERROR;
    }

    const sd_image_t& img = images[0];
    out.width = (int)img.width;
    out.height = (int)img.height;
    out.seed = params.seed;
    const size_t n = (size_t)img.width * img.height;
    out.rgba.resize(n * 4);
    if (img.channel == 3 || img.channel == 4) {
        for (size_t i = 0; i < n; ++i) {
            out.rgba[i * 4 + 0] = img.data[i * img.channel + 0];
            out.rgba[i * 4 + 1] = img.data[i * img.channel + 1];
            out.rgba[i * 4 + 2] = img.data[i * img.channel + 2];
            out.rgba[i * 4 + 3] = img.channel == 4 ? img.data[i * 4 + 3] : 255;
        }
    } else {
        free_sd_images(images, n_images);
        error = "unexpected channel count " + std::to_string(img.channel);
        return GenStatus::ERROR;
    }
    free_sd_images(images, n_images);
    // A cancel that landed after the last graph still produced a full image: report it as done.
    return GenStatus::OK;
}

}  // namespace inferno_sd
