// Placeholder JNI surface; replaced by the engine implementation.
#include <jni.h>
#include <string>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_to_eyed_inferno_engine_LlamaNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

