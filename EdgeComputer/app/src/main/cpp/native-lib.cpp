#include <jni.h>
#include <android/log.h>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EdgeNative", __VA_ARGS__)

static std::atomic<long> gCount{0};

extern "C"
JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_nativeOnFrameNV21(
        JNIEnv* env,
        jobject /*thiz*/,
        jbyteArray nv21,
        jint width,
        jint height) {

    long n = ++gCount;

    // Accès bytes (lecture seulement)
    jbyte* ptr = env->GetByteArrayElements(nv21, nullptr);
    jsize len = env->GetArrayLength(nv21);

    if (n % 30 == 0) {
        LOGI("Frames=%ld | %dx%d | len=%d", n, width, height, (int)len);
    }

    env->ReleaseByteArrayElements(nv21, ptr, JNI_ABORT);
}
