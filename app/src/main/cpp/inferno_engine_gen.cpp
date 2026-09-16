// Engine: prompt rendering, tokenisation, KV prefix reuse with state checkpoints, generation.
#include "inferno_engine.h"

#include <algorithm>
#include <cstring>

#include "inferno_log.h"

namespace inferno {

namespace {

constexpr size_t MAX_CHECKPOINTS = 3;
constexpr int64_t ENCODE_HEADROOM_BYTES = 400LL << 20;

std::string trim(const std::string & s) {
    size_t b = 0, e = s.size();
    while (b < e && (unsigned char) s[b] <= ' ') b++;
    while (e > b && (unsigned char) s[e - 1] <= ' ') e--;
    return s.substr(b, e - b);
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t utf8_complete_prefix(const std::string & s) {
    for (size_t k = 0; k < 4 && k < s.size(); k++) {
        const unsigned char c = (unsigned char) s[s.size() - 1 - k];
        if ((c & 0xC0) == 0x80) {
            continue;                           // continuation byte: keep looking for the lead
        }
        const int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
        return ((int) k + 1 < need) ? s.size() - 1 - k : s.size();
    }
    return s.size();
}

} // namespace

// ----------------------------------------------------------------------------------------------- templates

// Hand-rolled from the canonical Gemma 4 Jinja (tokenizer.chat_template, 2026-07-09): the vocab has
// <|turn> / <turn|> turn delimiters (no <start_of_turn>), the first system/developer message becomes a
// system turn, consecutive assistant messages merge, and "<|think|>" at the start of the system content
// enables thinking exactly like enable_thinking=true does.
std::string Engine::render_gemma4(const std::vector<ChatMsg> & msgs, bool add_ass) const {
    std::string out;
    size_t start = 0;
    if (!msgs.empty() && (msgs[0].role == "system" || msgs[0].role == "developer")) {
        std::string c = trim(msgs[0].content);
        out += "<|turn>system\n";
        static const std::string think = "<|think|>";
        if (c.compare(0, think.size(), think) == 0) {
            out += think + "\n";
            c = trim(c.substr(think.size()));
        }
        out += c + "<turn|>\n";
        start = 1;
    }
    std::string prev_role;
    for (size_t i = start; i < msgs.size(); i++) {
        const ChatMsg & m = msgs[i];
        if (m.role == "tool") {
            continue;
        }
        const std::string role = m.role == "assistant" ? "model" : m.role;
        if (!(role == "model" && prev_role == "assistant")) {
            out += "<|turn>" + role + "\n";
        }
        out += trim(m.content);
        std::string next_role;
        for (size_t j = i + 1; j < msgs.size(); j++) {
            if (msgs[j].role != "tool") { next_role = msgs[j].role; break; }
        }
        if (!(role == "model" && next_role == "assistant")) {
            out += "<turn|>\n";
        }
        prev_role = m.role;
    }
    if (add_ass) {
        out += "<|turn>model\n";
    }
    return out;
}

bool Engine::render_prompt(const std::vector<ChatMsg> & msgs, bool add_ass, std::string & out) {
    if (!model_) {
        set_error("no model");
        return false;
    }
    // Each user message gets one media marker per image in front ("most models require the marker
    // before each image"); mtmd later replaces the markers with the model's own image tokens.
    const std::string marker = mtmd_default_marker();
    std::vector<ChatMsg> cm(msgs.size());
    size_t total_chars = 0;
    for (size_t i = 0; i < msgs.size(); i++) {
        cm[i].role = msgs[i].role;
        std::string c;
        for (size_t k = 0; k < msgs[i].image_ids.size(); k++) c += marker;
        if (!msgs[i].image_ids.empty()) c += "\n";
        c += msgs[i].content;
        total_chars += c.size() + cm[i].role.size();
        cm[i].content = std::move(c);
    }
    if (tmpl_gemma4_) {
        out = render_gemma4(cm, add_ass);
        return true;
    }
    std::vector<llama_chat_message> lm(cm.size());
    for (size_t i = 0; i < cm.size(); i++) {
        lm[i] = { cm[i].role.c_str(), cm[i].content.c_str() };
    }
    const char * tmpl = nullptr;                       // nullptr => chatml
    if (tmpl_supported_) {
        tmpl = tmpl_override_.empty() ? tmpl_.c_str() : tmpl_override_.c_str();
    }
    std::vector<char> buf(total_chars * 2 + 1024);
    int32_t n = llama_chat_apply_template(tmpl, lm.data(), lm.size(), add_ass, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n + 1);
        n = llama_chat_apply_template(tmpl, lm.data(), lm.size(), add_ass, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        set_error("chat template failed");
        return false;
    }
    out.assign(buf.data(), (size_t) n);
    return true;
}

bool Engine::apply_chat_template(const std::vector<ChatMsg> & msgs, bool add_ass, std::string & out) {
    return render_prompt(msgs, add_ass, out);
}

// ----------------------------------------------------------------------------------------------- tokenisation

bool Engine::tokenize_text(const std::string & text, bool add_special, bool parse_special, std::vector<llama_token> & out) {
    out.resize(text.size() + 8);
    int32_t n = llama_tokenize(vocab_, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), add_special, parse_special);
    if (n < 0) {
        out.resize((size_t) -n);
        n = llama_tokenize(vocab_, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), add_special, parse_special);
    }
    if (n < 0) {
        set_error("tokenize failed");
        return false;
    }
    out.resize((size_t) n);
    return true;
}

int Engine::token_count(const std::string & text, bool add_special, bool parse_special) {
    if (!vocab_) {
        set_error("no model");
        return -1;
    }
    const int32_t n = llama_tokenize(vocab_, text.data(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    return n < 0 ? -n : n;
}

bool Engine::tokenize_prompt(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs,
                             const std::string & assistant_prefix, Prompt & out) {
    std::string prompt;
    if (!render_prompt(msgs, /*add_ass*/ true, prompt)) {
        return false;
    }
    out.tokens.clear();
    out.media.clear();
    out.chunks.clear();
    out.n_prefix = 0;

    // The rendered text is tokenised with add_special=false: built-in templates of BOS-bearing models already
    // emit the BOS text and add_special=true would double it. BOS is prepended only when the vocab wants it
    // and the text does not start with the BOS piece.
    bool need_bos = llama_vocab_get_add_bos(vocab_);
    if (need_bos) {
        const char * bos_text = llama_vocab_get_text(vocab_, llama_vocab_bos(vocab_));
        if (bos_text && *bos_text && prompt.compare(0, strlen(bos_text), bos_text) == 0) {
            need_bos = false;
        }
    }
    if (need_bos) {
        out.tokens.push_back(llama_vocab_bos(vocab_));
    }

    bool has_images = false;
    for (const auto & m : msgs) {
        if (!m.image_ids.empty()) has_images = true;
    }

    if (!mctx_) {
        if (has_images) {
            set_error("prompt has images but no projector is loaded");
            return false;
        }
        std::vector<llama_token> toks;
        if (!tokenize_text(prompt, false, true, toks)) {
            return false;
        }
        out.tokens.insert(out.tokens.end(), toks.begin(), toks.end());
    } else {
        // bitmaps in prompt order (marker order == message order == image_ids order)
        std::vector<mtmd::bitmap> bitmaps;
        for (const auto & m : msgs) {
            for (const auto & id : m.image_ids) {
                auto it = std::find_if(imgs.begin(), imgs.end(), [&](const ImageRGB & im) { return im.id == id; });
                if (it == imgs.end()) {
                    set_error("missing image " + id);
                    return false;
                }
                if (!it->rgb.empty() && it->rgb.size() != (size_t) it->w * it->h * 3) {
                    set_error("image " + id + " is not packed RGB888");
                    return false;
                }
                mtmd::bitmap b(it->w, it->h, it->rgb.empty() ? nullptr : it->rgb.data());   // nullptr => placeholder
                b.set_id(it->id.c_str());
                bitmaps.emplace_back(std::move(b));
            }
        }
        // split the rendered prompt on the marker and interleave text / bitmap parts (mtmd-cli approach)
        const std::string marker = mtmd_default_marker();
        std::vector<std::string> segs;
        size_t start = 0, pos;
        while ((pos = prompt.find(marker, start)) != std::string::npos) {
            segs.push_back(prompt.substr(start, pos - start));
            start = pos + marker.size();
        }
        segs.push_back(prompt.substr(start));
        if (segs.size() - 1 != bitmaps.size()) {
            set_error("marker/image count mismatch");
            return false;
        }
        std::vector<mtmd_input_text> texts(segs.size());
        std::vector<mtmd_input_part> parts;
        parts.reserve(segs.size() * 2);
        for (size_t i = 0; i < segs.size(); i++) {
            texts[i] = { segs[i].data(), segs[i].size(), /*add_special*/ false, /*parse_special*/ true };
            parts.push_back({ &texts[i], nullptr });
            if (i < bitmaps.size()) {
                parts.push_back({ nullptr, bitmaps[i].ptr.get() });
            }
        }
        std::vector<const mtmd_input_part *> pp;
        for (auto & p : parts) pp.push_back(&p);
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        const int32_t res = mtmd_tokenize_from_parts(mctx_, chunks.ptr.get(), pp.data(), pp.size(), /*add_special*/ false);
        if (res != 0) {
            set_error("mtmd_tokenize failed: " + std::to_string(res));
            return false;
        }
        for (size_t i = 0; i < chunks.size(); i++) {
            const mtmd_input_chunk * c = chunks[i];
            if (mtmd_input_chunk_get_type(c) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n = 0;
                const llama_token * t = mtmd_input_chunk_get_tokens_text(c, &n);
                out.tokens.insert(out.tokens.end(), t, t + n);
            } else {
                const size_t idx = out.tokens.size();
                const size_t n_tok = mtmd_input_chunk_get_n_tokens(c);
                const char * id = mtmd_input_chunk_get_id(c);
                out.media[idx] = { id ? id : "", n_tok, mtmd_input_chunk_get_n_pos(c) };
                out.chunks[idx] = mtmd::input_chunk_ptr(mtmd_input_chunk_copy(c));   // keeps the bitmap for encoding
                out.tokens.insert(out.tokens.end(), n_tok, LLAMA_TOKEN_NULL);
            }
        }
    }

    // The assistant prefix (e.g. "<think>\n") is tokenised separately so its token count is known: the
    // pre-decode checkpoint is placed right before it (the next turn diverges exactly there).
    if (!assistant_prefix.empty()) {
        std::vector<llama_token> pt;
        if (!tokenize_text(assistant_prefix, false, true, pt)) {
            return false;
        }
        out.tokens.insert(out.tokens.end(), pt.begin(), pt.end());
        out.n_prefix = pt.size();
    }
    return true;
}

int Engine::prompt_token_count(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs) {
    if (!ctx_) {
        set_error("no context");
        return -1;
    }
    cancel_.store(false, std::memory_order_relaxed);
    Prompt p;
    if (!tokenize_prompt(msgs, imgs, std::string(), p)) {
        return -1;
    }
    return (int) p.tokens.size();
}

// ----------------------------------------------------------------------------------------------- cache

llama_pos Engine::pos_at(const std::map<size_t, Media> & media, size_t idx) {
    // M-RoPE images occupy n_tokens cells but only n_pos positions
    llama_pos p = (llama_pos) idx;
    for (const auto & [start, m] : media) {
        if (start + m.n_tokens <= idx) {
            p -= (llama_pos) m.n_tokens - m.n_pos;
        } else {
            break;
        }
    }
    return p;
}

size_t Engine::common_prefix(const Prompt & p) const {
    const size_t max_idx = std::min(cache_.size(), p.tokens.size());
    for (size_t i = 0; i < max_idx; i++) {
        const llama_token a = cache_[i], b = p.tokens[i];
        if (a == LLAMA_TOKEN_NULL && b == LLAMA_TOKEN_NULL) {
            auto ia = cache_media_.find(i);
            auto ib = p.media.find(i);
            if (ia == cache_media_.end() || ib == p.media.end()) return i;      // not both chunk starts
            if (ia->second.id != ib->second.id || ia->second.n_tokens != ib->second.n_tokens) return i;
            if (i + ia->second.n_tokens > max_idx) return i;                     // chunk does not fit in both
            i += ia->second.n_tokens - 1;                                        // never stop inside an image
            continue;
        }
        if (a != b) return i;
    }
    return max_idx;
}

void Engine::truncate_cache(size_t n) {
    if (n < cache_.size()) {
        cache_.resize(n);
    }
    for (auto it = cache_media_.begin(); it != cache_media_.end();) {
        it = (it->first >= n) ? cache_media_.erase(it) : std::next(it);
    }
    n_past_ = pos_next(cache_.size());
    drop_checkpoints_above(cache_.size());
}

void Engine::drop_checkpoints_above(size_t n_tokens) {
    ckpts_.erase(std::remove_if(ckpts_.begin(), ckpts_.end(),
                                [&](const Checkpoint & c) { return c.n_tokens > n_tokens; }),
                 ckpts_.end());
}

void Engine::take_checkpoint() {
    if (!needs_checkpoints_ || !ctx_ || cache_.empty()) {
        return;
    }
    if (!ckpts_.empty() && ckpts_.back().n_tokens == cache_.size()) {
        return;
    }
    const size_t size = llama_state_seq_get_size_ext(ctx_, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (size == 0) {
        return;
    }
    Checkpoint c;
    c.n_tokens = cache_.size();
    c.pos_min  = llama_memory_seq_pos_min(llama_get_memory(ctx_), 0);   // iSWA reports the SWA cache's minimum
    c.pos_max  = llama_memory_seq_pos_max(llama_get_memory(ctx_), 0);
    c.data.resize(size);
    const size_t n = llama_state_seq_get_data_ext(ctx_, c.data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (n == 0) {
        LOGW("checkpoint capture failed");
        return;
    }
    c.data.resize(n);
    ckpts_.push_back(std::move(c));
    while (ckpts_.size() > MAX_CHECKPOINTS) {
        ckpts_.erase(ckpts_.begin());
    }
    LOGD("checkpoint: n_tokens=%zu pos_min=%d pos_max=%d bytes=%zu", cache_.size(), (int) ckpts_.back().pos_min,
         (int) ckpts_.back().pos_max, n);
}

bool Engine::rewind_to(size_t & n_target) {
    llama_memory_t mem = llama_get_memory(ctx_);
    if (n_target > cache_.size()) {
        n_target = cache_.size();
    }
    const llama_pos p0 = pos_next(n_target);
    if (n_target == cache_.size() && llama_memory_seq_pos_max(mem, 0) + 1 == p0) {
        return true;                                                     // nothing to remove
    }
    // iSWA (swa_full=false): seq_rm never refuses, but the SWA ring only holds ~n_swa+n_ubatch positions and
    // decoding at p0 needs [p0-n_swa, p0) in the SWA layers, so mirror llama-server's pos_min check. A short
    // window leaves the base cache untouched until a checkpoint's SWA state is restored below.
    const int32_t n_swa = (cparams_.swa_full || !model_) ? 0 : llama_model_n_swa(model_);
    auto swa_window_ok = [&](llama_pos pos_min, llama_pos pos_resume) {
        return n_swa <= 0 || pos_min <= 0 || pos_min <= std::max<llama_pos>(0, pos_resume - n_swa);
    };
    if (swa_window_ok(llama_memory_seq_pos_min(mem, 0), p0) && llama_memory_seq_rm(mem, 0, p0, -1)) {
        truncate_cache(n_target);                                        // pure attention, or n_rs_seq rewind
        return true;
    }
    // Refused (hybrid / recurrent) or SWA window incomplete: restore the newest checkpoint at or below the
    // target and re-decode from there.
    for (auto it = ckpts_.rbegin(); it != ckpts_.rend(); ++it) {
        if (it->n_tokens > n_target) {
            continue;
        }
        if (!swa_window_ok(it->pos_min, it->pos_max + 1)) {
            continue;                                                    // its own SWA window is already incomplete
        }
        const size_t r = llama_state_seq_set_data_ext(ctx_, it->data.data(), it->data.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        if (r == 0) {
            LOGW("checkpoint restore failed (n_tokens=%zu)", it->n_tokens);
            break;
        }
        if (!llama_memory_seq_rm(mem, 0, it->pos_max + 1, -1)) {
            LOGW("seq_rm after checkpoint restore refused (pos_max=%d)", (int) it->pos_max);
            break;
        }
        LOGI("restored checkpoint n_tokens=%zu for target %zu (cache %zu)", it->n_tokens, n_target, cache_.size());
        n_target = it->n_tokens;
        truncate_cache(n_target);
        return true;
    }
    LOGW("full KV clear: no usable checkpoint for target %zu (cache %zu)", n_target, cache_.size());
    llama_memory_clear(mem, true);
    cache_.clear();
    cache_media_.clear();
    ckpts_.clear();
    n_past_ = 0;
    n_target = 0;
    n_full_clears_++;
    return true;
}

void Engine::resync_after_abort() {
    // llama_decode returned 2 (or the encoder was cut short): the processed ubatches stayed in memory.
    llama_memory_t mem = llama_get_memory(ctx_);
    const llama_pos pmax = llama_memory_seq_pos_max(mem, 0);
    if (pmax + 1 == pos_next(cache_.size())) {
        return;
    }
    size_t n = cache_.size();
    rewind_to(n);
}

void Engine::kv_clear() {
    if (ctx_) {
        llama_memory_clear(llama_get_memory(ctx_), /*data*/ true);
    }
    cache_.clear();
    cache_media_.clear();
    ckpts_.clear();
    n_past_ = 0;
    if (smpl_) {
        llama_sampler_reset(smpl_);
    }
    if (grmr_) {
        llama_sampler_reset(grmr_);
    }
}

// ----------------------------------------------------------------------------------------------- prefill

bool Engine::decode_text(const llama_token * toks, size_t n, bool logits_last, Progress cb, void * ud, int done, int total) {
    const size_t n_batch = cparams_.n_batch;
    for (size_t i = 0; i < n; i += n_batch) {
        if (cancel_.load(std::memory_order_relaxed)) {
            finish_ = Finish::cancelled;
            return false;
        }
        const int32_t cur = (int32_t) std::min<size_t>(n_batch, n - i);
        batch_.n_tokens = 0;
        for (int32_t j = 0; j < cur; j++) {
            const int32_t k = batch_.n_tokens++;
            batch_.token[k]     = toks[i + j];
            batch_.pos[k]       = n_past_ + j;
            batch_.n_seq_id[k]  = 1;
            batch_.seq_id[k][0] = 0;
            batch_.logits[k]    = (logits_last && i + j == n - 1) ? 1 : 0;   // one output at most (n_outputs_max)
        }
        const int32_t ret = llama_decode(ctx_, batch_);
        if (ret == 2) {
            finish_ = Finish::cancelled;                    // aborted: processed ubatches stay in KV (resync)
            return false;
        }
        if (ret == 1) {
            finish_ = Finish::context_full;
            set_error("no KV slot for the prompt batch");
            return false;
        }
        if (ret != 0) {
            finish_ = Finish::error;
            set_error("llama_decode failed: " + std::to_string(ret));
            return false;
        }
        cache_.insert(cache_.end(), toks + i, toks + i + cur);
        n_past_ += cur;
        if (cb && !cb(done + (int) (i + cur), total, PHASE_TEXT, ud)) {
            cancel_.store(true, std::memory_order_relaxed);
            finish_ = Finish::cancelled;
            return false;
        }
    }
    return true;
}

int Engine::generate_start(const std::vector<ChatMsg> & msgs, const std::vector<ImageRGB> & imgs, int n_predict,
                           const std::string & assistant_prefix, int64_t avail_mem, Progress cb, void * ud) {
    if (!ctx_) {
        set_error("no context");
        return -1;
    }
    cancel_.store(false, std::memory_order_relaxed);
    clip_node_counter_.store(0, std::memory_order_relaxed);
    finish_ = Finish::running;
    utf8_pending_.clear();
    n_gen_ = 0;
    n_predict_ = n_predict;
    t_prefill_us_ = t_decode_us_ = t_image_us_ = 0;
    n_prompt_ = n_reused_ = 0;
    const int64_t t0 = ggml_time_us();

    bool has_images = false;
    for (const auto & m : msgs) {
        if (!m.image_ids.empty()) has_images = true;
    }
    if (has_images && !mctx_) {
        finish_ = Finish::error;
        set_error("prompt has images but no projector is loaded");
        return -6;
    }
    Prompt p;
    if (!tokenize_prompt(msgs, imgs, assistant_prefix, p)) {
        finish_ = Finish::error;
        return -2;
    }
    for (const auto & [idx, m] : p.media) {
        if (m.n_tokens > cparams_.n_batch) {
            // never let the helper split a non-causal image across batches (silently degrades vision)
            finish_ = Finish::error;
            set_error("image chunk of " + std::to_string(m.n_tokens) + " tokens exceeds n_batch " + std::to_string(cparams_.n_batch));
            return -4;
        }
    }
    // capacity: the whole prompt plus a minimum generation budget must fit
    const llama_pos n_pos_prompt = pos_at(p.media, p.tokens.size());
    const int n_predict_min = n_predict > 0 ? std::min(n_predict, 256) : 256;
    if (n_pos_prompt + n_predict_min > (llama_pos) cparams_.n_ctx) {
        finish_ = Finish::error;
        set_error("prompt does not fit: " + std::to_string(n_pos_prompt) + " + " + std::to_string(n_predict_min) +
                  " > " + std::to_string(cparams_.n_ctx));
        return 2;
    }

    // 1. longest common prefix with the cache; a full match backs up one token (or a whole image) for logits
    size_t n_keep = common_prefix(p);
    if (n_keep == p.tokens.size() && n_keep > 0) {
        n_keep -= 1;
        if (p.tokens[n_keep] == LLAMA_TOKEN_NULL) {
            auto it = p.media.upper_bound(n_keep);
            --it;
            n_keep = it->first;
        }
    }
    // 2. drop the stale suffix (seq_rm, checkpoint restore, or full clear); n_keep may shrink
    rewind_to(n_keep);
    // 3. pre-encode memory check for the images that will actually be (re-)encoded. Done after the rewind so
    // a checkpoint restore / full clear that forces a re-encode cannot bypass it; returning here is safe
    // because rewind_to leaves cache_/cache_media_/n_past_ consistent with the KV memory.
    bool needs_encode = false;
    for (const auto & [idx, m] : p.media) {
        if (idx >= n_keep) needs_encode = true;
    }
    if (needs_encode && avail_mem > 0 && mctx_) {
        MmprojEstimate m;
        if (mmproj_estimate(mmproj_path_, m) && avail_mem < m.compute_bytes + ENCODE_HEADROOM_BYTES) {
            finish_ = Finish::error;
            set_error("not enough free memory for the image encode (" + std::to_string(avail_mem >> 20) + " MB)");
            return -5;
        }
    }
    n_prompt_ = (int) p.tokens.size();
    n_reused_ = (int) n_keep;

    // Pre-decode checkpoint right before the assistant prefix / the last prompt token: the next turn (regenerate,
    // thinking prefix replaced by the stored answer) diverges exactly there, so the restore re-decodes 1-2 tokens.
    size_t ckpt_idx = p.tokens.size() - std::max<size_t>(1, p.n_prefix);
    if (ckpt_idx < n_keep || p.tokens[ckpt_idx] == LLAMA_TOKEN_NULL) {
        ckpt_idx = SIZE_MAX;
    }

    // 4. evaluate the rest chunk by chunk
    size_t i = n_keep;
    const int total = (int) p.tokens.size();
    while (i < p.tokens.size()) {
        if (cancel_.load(std::memory_order_relaxed)) {
            finish_ = Finish::cancelled;
            break;
        }
        if (i == ckpt_idx) {
            take_checkpoint();
        }
        auto mit = p.media.find(i);
        if (mit != p.media.end()) {
            const mtmd_input_chunk * chunk = p.chunks.at(i).get();
            const size_t n_tok = mit->second.n_tokens;
            const bool last = (i + n_tok == p.tokens.size());
            if (cb && !cb((int) i, total, PHASE_IMAGE, ud)) {
                cancel_.store(true, std::memory_order_relaxed);
                finish_ = Finish::cancelled;
                break;
            }
            take_checkpoint();      // a cancelled encode on a hybrid model rewinds here instead of clearing
            const int64_t ti = ggml_time_us();
            llama_pos new_past = n_past_;
            const int32_t r = mtmd_helper_eval_chunk_single(mctx_, ctx_, chunk, n_past_, 0, (int32_t) cparams_.n_batch, last, &new_past);
            t_image_us_ += ggml_time_us() - ti;
            if (r != 0) {
                if (cancel_.load(std::memory_order_relaxed)) {
                    finish_ = Finish::cancelled;
                } else {
                    finish_ = Finish::error;
                    set_error("image eval failed: " + std::to_string(r));
                }
                break;
            }
            n_past_ = new_past;
            cache_media_[cache_.size()] = mit->second;
            cache_.insert(cache_.end(), n_tok, LLAMA_TOKEN_NULL);
            i += n_tok;
            if (cb && !cb((int) i, total, PHASE_IMAGE, ud)) {
                cancel_.store(true, std::memory_order_relaxed);
                finish_ = Finish::cancelled;
                break;
            }
        } else {
            size_t end = p.tokens.size();
            if (ckpt_idx > i && ckpt_idx < end) end = ckpt_idx;          // stop so the checkpoint lands exactly there
            size_t j = i;
            while (j < end && p.tokens[j] != LLAMA_TOKEN_NULL) j++;
            const bool last = (j == p.tokens.size());
            if (!decode_text(&p.tokens[i], j - i, last, cb, ud, (int) i, total)) {
                break;
            }
            i = j;
        }
    }
    if (finish_ != Finish::running) {
        resync_after_abort();
        if (finish_ == Finish::cancelled) return 1;
        if (finish_ == Finish::context_full) return 2;
        return -3;
    }

    // 5. sampler sees the newly evaluated text tokens (penalties / DRY history). The grammar is only reset:
    // feeding it prompt tokens would empty its stacks (std::runtime_error / GGML_ABORT), like common_sampler.
    llama_sampler_reset(smpl_);
    if (grmr_) {
        llama_sampler_reset(grmr_);
    }
    for (size_t k = n_keep; k < cache_.size(); k++) {
        if (cache_[k] != LLAMA_TOKEN_NULL) {
            llama_sampler_accept(smpl_, cache_[k]);
        }
    }
    t_prefill_us_ = ggml_time_us() - t0;
    return 0;
}

// ----------------------------------------------------------------------------------------------- generation

bool Engine::generate_next(std::string & piece_out) {
    piece_out.clear();
    if (finish_ != Finish::running || !ctx_) {
        return false;
    }
    if (cancel_.load(std::memory_order_relaxed)) {
        finish_ = Finish::cancelled;
        take_checkpoint();
        return false;
    }
    if (n_predict_ > 0 && n_gen_ >= n_predict_) {
        finish_ = Finish::length;
        take_checkpoint();
        return false;
    }
    if (n_past_ + 1 > (llama_pos) cparams_.n_ctx) {
        finish_ = Finish::context_full;
        take_checkpoint();
        return false;
    }
    const int64_t t0 = ggml_time_us();
    llama_token id;
    if (!grmr_) {
        id = llama_sampler_sample(smpl_, ctx_, -1);                        // applies the chain + accepts
    } else {
        // Grammar path (common_sampler order): constrain the candidates first, then run the chain, and only
        // feed the generated token to the grammar (never prompt tokens, see generate_start step 5).
        const float * logits = llama_get_logits_ith(ctx_, -1);
        const int n_vocab = llama_vocab_n_tokens(vocab_);
        cur_.resize((size_t) n_vocab);
        for (int t = 0; t < n_vocab; t++) {
            cur_[(size_t) t] = { t, logits[t], 0.0f };
        }
        llama_token_data_array cur_p = { cur_.data(), cur_.size(), -1, false };
        llama_sampler_apply(grmr_, &cur_p);
        llama_sampler_apply(smpl_, &cur_p);
        GGML_ASSERT(cur_p.selected >= 0 && cur_p.selected < (int64_t) cur_p.size);
        id = cur_p.data[cur_p.selected].id;
        llama_sampler_accept(smpl_, id);
        llama_sampler_accept(grmr_, id);
    }
    if (llama_vocab_is_eog(vocab_, id)) {
        finish_ = Finish::eos;                                             // EOS is not decoded into the KV
        take_checkpoint();
        return false;
    }
    batch_.n_tokens     = 1;
    batch_.token[0]     = id;
    batch_.pos[0]       = n_past_;
    batch_.n_seq_id[0]  = 1;
    batch_.seq_id[0][0] = 0;
    batch_.logits[0]    = 1;                                               // the only output of this batch
    const int32_t ret = llama_decode(ctx_, batch_);
    if (ret != 0) {
        if (ret == 2) {
            finish_ = Finish::cancelled;
        } else if (ret == 1) {
            finish_ = Finish::context_full;
        } else {
            finish_ = Finish::error;
            set_error("llama_decode failed: " + std::to_string(ret));
        }
        resync_after_abort();
        return false;
    }
    n_past_++;
    n_gen_++;
    cache_.push_back(id);

    char buf[512];
    const int32_t n = llama_token_to_piece(vocab_, id, buf, sizeof(buf), 0, /*special*/ false);
    if (n > 0) {
        utf8_pending_.append(buf, (size_t) n);
    }
    // emit only complete UTF-8 sequences; the incomplete tail waits for the next token
    const size_t cut = utf8_complete_prefix(utf8_pending_);
    piece_out.assign(utf8_pending_, 0, cut);
    utf8_pending_.erase(0, cut);
    t_decode_us_ += ggml_time_us() - t0;
    return true;
}

void Engine::stats(GenStats & out) const {
    out.v[0] = n_prompt_;
    out.v[1] = n_reused_;
    out.v[2] = n_gen_;
    out.v[3] = t_prefill_us_ / 1000.0;
    out.v[4] = t_decode_us_ / 1000.0;
    out.v[5] = t_image_us_ / 1000.0;
    out.v[6] = (double) cache_.size();
    out.v[7] = (double) n_past_;
}

} // namespace inferno
