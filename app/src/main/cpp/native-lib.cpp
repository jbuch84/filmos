#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "NDK_ENGINE"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_github_ma1co_pmcademo_app_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("C++ Engine Online");
}