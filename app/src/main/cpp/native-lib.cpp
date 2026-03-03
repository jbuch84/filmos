#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_ma1co_pmcademo_app_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "C++ Engine Online";
    return env->NewStringUTF(hello.c_str());
}