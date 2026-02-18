#include <jni.h>
#include <android/native_window_jni.h>
#include "Camera.h"

static Camera gCamera;

extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_nativeSetPreviewSurface(
        JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    gCamera.setPreviewWindow(win);
    // setPreviewWindow() fait acquire, donc on peut release ici
    ANativeWindow_release(win);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_nativeStartCamera(
        JNIEnv*, jclass, jint width, jint height) {
    gCamera.start((int)width, (int)height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_nativeStopCamera(
        JNIEnv*, jclass) {
    gCamera.stop();
}
