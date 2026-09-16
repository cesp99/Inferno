// Placeholder JNI surface for the image-generation engine; replaced by the real implementation.
#include <jni.h>
#include "stable-diffusion.h"

extern "C" JNIEXPORT jstring JNICALL
Java_to_eyed_inferno_sd_SdNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(sd_get_system_info());
}
