#include "inferno_log.h"

#include <atomic>
#include <mutex>
#include <string>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace inferno {

namespace {

std::atomic<int> g_min_prio{ANDROID_LOG_INFO};

// ggml emits many messages as fragments (GGML_LOG_LEVEL_CONT) that only make sense when joined;
// buffer until '\n' so logcat gets whole lines instead of a flood of pieces. The loggers may be
// called from worker threads (e.g. the clip encoder), hence the mutex.
std::mutex   g_mutex;
std::string  g_pending;
int          g_pending_prio = ANDROID_LOG_INFO;

// Developer-mode "Engine" card: llama.cpp reports which buffer types the weights landed in
// ("load_tensors: CPU_KLEIDIAI model buffer size = ...") only through the log, so the matching lines of
// the last model load are kept here regardless of the priority floor. Cleared by Engine::model_load.
std::string  g_notes;
constexpr size_t NOTES_MAX = 2048;

void note_line(const std::string & line) {
    if (line.find("model buffer size") == std::string::npos || g_notes.size() > NOTES_MAX) {
        return;
    }
    g_notes += line;
    g_notes += '\n';
}

int to_android_prio(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        default:                   return ANDROID_LOG_INFO;
    }
}

void flush_locked() {
    // strip trailing newlines: logcat adds its own
    while (!g_pending.empty() && (g_pending.back() == '\n' || g_pending.back() == '\r')) {
        g_pending.pop_back();
    }
    note_line(g_pending);
    if (!g_pending.empty() && g_pending_prio >= g_min_prio.load(std::memory_order_relaxed)) {
        __android_log_write(g_pending_prio, INF_TAG, g_pending.c_str());
    }
    g_pending.clear();
}

} // namespace

void log_callback(enum ggml_log_level level, const char * text, void * /*user*/) {
    if (text == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (level != GGML_LOG_LEVEL_CONT) {
        // a new message: flush whatever fragment was still pending (it never got its newline)
        flush_locked();
        g_pending_prio = to_android_prio(level);
    }
    g_pending += text;
    // emit every complete line, keep the incomplete tail
    size_t nl;
    while ((nl = g_pending.find('\n')) != std::string::npos) {
        std::string line = g_pending.substr(0, nl);
        g_pending.erase(0, nl + 1);
        note_line(line);
        if (!line.empty() && g_pending_prio >= g_min_prio.load(std::memory_order_relaxed)) {
            __android_log_write(g_pending_prio, INF_TAG, line.c_str());
        }
    }
}

void log_set_min_priority(int min_android_prio) {
    if (min_android_prio < ANDROID_LOG_VERBOSE) min_android_prio = ANDROID_LOG_VERBOSE;
    if (min_android_prio > ANDROID_LOG_FATAL)   min_android_prio = ANDROID_LOG_FATAL;
    g_min_prio.store(min_android_prio, std::memory_order_relaxed);
}

void log_clear_notes() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_notes.clear();
}

std::string log_notes() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_notes;
}

void log_install(int min_android_prio) {
    log_set_min_priority(min_android_prio);
    llama_log_set(log_callback, nullptr);        // llama + ggml
    mtmd_log_set(log_callback, nullptr);         // clip / mmproj loader + encoder (default sink is stderr, invisible on Android)
    mtmd_helper_log_set(log_callback, nullptr);  // mtmd-helper (eval / decode messages)
}

} // namespace inferno
