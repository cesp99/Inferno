// Engine: in-app benchmark.
#include "inferno_engine.h"

#include <algorithm>
#include <random>
#include <string>

#include "inferno_log.h"

namespace inferno {

namespace {

int argmax(const float * logits, int n) {
    int best = 0;
    for (int i = 1; i < n; i++) {
        if (logits[i] > logits[best]) best = i;
    }
    return best;
}

double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    std::sort(v.begin(), v.end());
    const size_t n = v.size();
    return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

} // namespace

// ----------------------------------------------------------------------------------------------- bench

bool Engine::bench(int n_prompt, int n_gen, int reps, Progress cb, void * ud, double out[4]) {
    if (!ctx_) {
        set_error("no context");
        return false;
    }
    if (n_prompt < 1 || n_gen < 1 || reps < 1 || (uint32_t) (n_prompt + n_gen) > cparams_.n_ctx) {
        set_error("bench: invalid sizes");
        return false;
    }
    cancel_.store(false, std::memory_order_relaxed);
    kv_clear();
    llama_memory_t mem = llama_get_memory(ctx_);
    const int n_vocab = llama_vocab_n_tokens(vocab_);
    std::mt19937 rng(42);
    std::uniform_int_distribution<int> dist(0, n_vocab - 1);
    std::vector<double> pp_tps, tg_tps, pp_ms, tg_ms;

    auto fail = [&](const std::string & msg) { set_error(msg); kv_clear(); return false; };

    for (int rep = 0; rep < reps; rep++) {
        if (cancel_.load(std::memory_order_relaxed)) {
            return fail("bench cancelled");
        }
        std::vector<llama_token> toks((size_t) n_prompt);
        for (auto & t : toks) {
            do { t = dist(rng); } while (llama_vocab_is_control(vocab_, t));
        }
        // prompt: n_batch slices, logits only on the last token of the last slice
        const int64_t t0 = ggml_time_us();
        llama_pos pos = 0;
        for (int i = 0; i < n_prompt; i += (int) cparams_.n_batch) {
            const int32_t cur = std::min<int32_t>((int32_t) cparams_.n_batch, n_prompt - i);
            batch_.n_tokens = 0;
            for (int32_t j = 0; j < cur; j++) {
                const int32_t k = batch_.n_tokens++;
                batch_.token[k] = toks[(size_t) (i + j)];
                batch_.pos[k] = pos++;
                batch_.n_seq_id[k] = 1;
                batch_.seq_id[k][0] = 0;
                batch_.logits[k] = (i + j == n_prompt - 1) ? 1 : 0;
            }
            const int32_t ret = llama_decode(ctx_, batch_);
            if (ret == 2) return fail("bench cancelled");
            if (ret != 0) return fail("bench: prompt decode failed: " + std::to_string(ret));
        }
        const int64_t t1 = ggml_time_us();
        // generation: each step feeds the greedy argmax of the previous single logits row
        llama_token tok = argmax(llama_get_logits_ith(ctx_, -1), n_vocab);
        for (int g = 0; g < n_gen; g++) {
            batch_.n_tokens = 1;
            batch_.token[0] = tok;
            batch_.pos[0] = pos++;
            batch_.n_seq_id[0] = 1;
            batch_.seq_id[0][0] = 0;
            batch_.logits[0] = 1;
            const int32_t ret = llama_decode(ctx_, batch_);
            if (ret == 2) return fail("bench cancelled");
            if (ret != 0) return fail("bench: gen decode failed: " + std::to_string(ret));
            tok = argmax(llama_get_logits_ith(ctx_, -1), n_vocab);
        }
        const int64_t t2 = ggml_time_us();
        pp_ms.push_back((t1 - t0) / 1000.0);
        tg_ms.push_back((t2 - t1) / 1000.0);
        pp_tps.push_back(n_prompt * 1e6 / (double) std::max<int64_t>(t1 - t0, 1));
        tg_tps.push_back(n_gen * 1e6 / (double) std::max<int64_t>(t2 - t1, 1));
        llama_memory_clear(mem, true);
        if (cb && !cb(rep + 1, reps, PHASE_BENCH, ud)) {
            return fail("bench cancelled");
        }
    }
    kv_clear();
    out[0] = median(pp_tps);
    out[1] = median(tg_tps);
    out[2] = median(pp_ms);
    out[3] = median(tg_ms);
    LOGI("bench: pp%d %.1f t/s, tg%d %.1f t/s (%d reps)", n_prompt, out[0], n_gen, out[1], reps);
    return true;
}

} // namespace inferno
