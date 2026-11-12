#include <jni.h>
#include <string>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "TunnelNDK"

// নেটিভ কোড এখন শুধু দুটি নিরাপদ কাজ করে।
// (আগের fork()-ভিত্তিক কোড JVM প্রসেসে বিপজ্জনক ছিল এবং Kotlin থেকে কখনো ডাকা হতো না — বাদ দেওয়া হয়েছে;
//  প্রসেস চালানো হয় ProcessBuilder দিয়ে।)

extern "C" JNIEXPORT jstring JNICALL
Java_com_cloudflare_tunnel_TunnelManager_nativeGetVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("NDK Tunnel Manager v2.0");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cloudflare_tunnel_TunnelManager_nativeSetPermissions(JNIEnv *env, jobject /*thiz*/, jstring file_path) {
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    if (path == nullptr) return JNI_FALSE;
    int result = chmod(path, S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "chmod failed for %s", path);
    }
    env->ReleaseStringUTFChars(file_path, path);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}
