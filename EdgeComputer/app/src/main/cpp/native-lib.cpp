#include <jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <memory>
#include <thread>

#include "headers/Util.h"
#include "headers/CV_Manager.h"

// Manager global (une seule instance)
static std::unique_ptr<CV_Manager> gCv;
static ANativeWindow* gWindow = nullptr;
static int gWinW = 0;
static int gWinH = 0;

static std::thread gCameraThread;

static void startCameraThreadIfNeeded() {
    // Si un thread précédent est encore joinable, on le rejoint d'abord
    if (gCameraThread.joinable()) {
        gCameraThread.join();
    }
    // CV_Manager::CameraLoop() tourne en boucle jusqu'à m_camera_thread_stopped
    gCameraThread = std::thread([]() {
        if (gCv) {
            LOGI("CameraLoop thread started");
            gCv->CameraLoop();
            LOGI("CameraLoop thread ended");
        }
    });
}

static void cleanupAll() {
    if (gCv) {
        // Stop loop
        gCv->HaltCamera();
    }

    // Attendre que le thread se termine proprement
    if (gCameraThread.joinable()) {
        gCameraThread.join();
    }

    // Maintenant on peut détruire les ressources en toute sécurité
    if (gCv) {
        gCv.reset();
    }

    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
    gWinW = gWinH = 0;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

/**
 * Java: public native void setSurface(Surface surface);
 * Donne la surface de preview au natif + démarre camera + démarre la loop d'affichage/traitement.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_setSurface(
        JNIEnv* env, jobject /*thiz*/, jobject surface) {

    if (!surface) {
        LOGE("setSurface: surface == null");
        cleanupAll();
        return;
    }

    // Si on reçoit une nouvelle surface, on nettoie l'ancienne
    cleanupAll();

    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("setSurface: ANativeWindow_fromSurface failed");
        return;
    }

    // On garde une référence globale (acquire)
    gWindow = win;
    ANativeWindow_acquire(gWindow);

    gWinW = ANativeWindow_getWidth(gWindow);
    gWinH = ANativeWindow_getHeight(gWindow);
    LOGI("setSurface: window size %dx%d", gWinW, gWinH);

    // Crée le manager CV + branche la window
    gCv = std::make_unique<CV_Manager>();
    gCv->SetNativeWindow(gWindow);

    // Setup camera (Native_Camera + Image_Reader + capture session)
    gCv->SetUpCamera();

    // Lance la loop (lock window, DisplayImage, Mat buffer, etc.)
    startCameraThreadIfNeeded();
}

/**
 * Java: public native void scan();
 * Ici ton CV_Manager a déjà une fonction RunCV() (scan_mode).
 * Donc on mappe le bouton "scan" sur RunCV().
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_scan(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    if (!gCv) {
        LOGE("scan: CV_Manager not initialized (call setSurface first)");
        return;
    }
    LOGI("scan: RunCV()");
    gCv->RunCV();
}

/**
 * Java: public native void flipCamera();
 * Dans CV_Manager: FlipCamera() réinitialise et relance un thread.
 * Mais pour éviter les races, on demande d'abord d'arrêter la loop.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_flipCamera(
        JNIEnv* /*env*/, jobject /*thiz*/) {

    if (!gCv) {
        LOGE("flipCamera: CV_Manager not initialized (call setSurface first)");
        return;
    }

    LOGI("flipCamera: stopping loop");
    gCv->HaltCamera();
    if (gCameraThread.joinable()) {
        gCameraThread.join();
    }

    LOGI("flipCamera: flipping");
    gCv->FlipCamera();
    startCameraThreadIfNeeded();
}

/**
 * Optionnel mais pratique :
 * Java: public native void release();
 * À appeler dans onDestroy()/surfaceDestroyed() si tu veux clean proprement.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_edgecomputer_MainActivity_release(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("release()");
    cleanupAll();
}
