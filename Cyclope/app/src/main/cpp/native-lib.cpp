#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <thread>
#include <chrono>

#include "CV_Manager.h"

#define LOG_TAG "CameraNDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static CV_Manager app;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_cyclope_MainActivity_scan(JNIEnv *env, jobject thiz) {
    LOGD("scan() called");
    app.RunCV();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_cyclope_MainActivity_flipCamera(JNIEnv *env, jobject thiz) {
    LOGD("flipCamera() called");
    app.FlipCamera();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_cyclope_MainActivity_setGpsData(
        JNIEnv *env, jobject thiz,
        jdouble lat, jdouble lon, jdouble alt, jfloat accuracy) {
    app.SetGpsData((double)lat, (double)lon, (double)alt, (float)accuracy);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_cyclope_MainActivity_setSurface(JNIEnv *env, jobject thiz, jobject surface) {
    LOGD("setSurface() called");

    if (surface == nullptr) {
        LOGE("setSurface() -> surface is null");
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("setSurface() -> ANativeWindow_fromSurface returned null");
        return;
    }

    app.SetNativeWindow(window);

    // Empêche de recréer toute la caméra à chaque rappel surfaceCreated/surfaceChanged
    if (app.IsInitialized()) {
        LOGD("setSurface() -> already initialized, skipping camera re-init");
        return;
    }

    LOGD("setSurface() -> first initialization, setting up camera");

    app.SetInitialized(false); // explicite
    app.SetUpCamera();

    // Petite attente pour laisser la session caméra se mettre en place
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    app.StartCameraLoop();

    app.SetInitialized(true);

    LOGD("setSurface() -> initialization done");
}