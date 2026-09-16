#pragma once
// Logcat sink shared by ggml, llama and mtmd (tag "inferno-native").
#include <android/log.h>
#include "ggml.h"

#define INF_TAG "inferno-native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, INF_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  INF_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  INF_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, INF_TAG, __VA_ARGS__)

namespace inferno {

// Installs the callback on all three loggers (llama+ggml, mtmd, mtmd-helper). Lines below
// `min_android_prio` (android.util.Log priority, DEBUG=3 .. ERROR=6) are dropped.
void log_install(int min_android_prio);
void log_set_min_priority(int min_android_prio);

// The ggml_log_callback itself (exposed for re-installation).
void log_callback(enum ggml_log_level level, const char * text, void * user);

} // namespace inferno
