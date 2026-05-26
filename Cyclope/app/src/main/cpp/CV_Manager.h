//
// Created by ladiaviakoye on 22/03/2026.
//

#ifndef CYCLOPE_CV_MANAGER_H
#define CYCLOPE_CV_MANAGER_H
#include <android/native_window.h>
#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "Image_Reader.h"
#include "Native_Camera.h"
#include "Util.h"
#include <cstdlib>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <chrono>


using namespace cv;
using namespace std;

class CV_Manager {
public:
    CV_Manager();
    ~CV_Manager();
    CV_Manager(const CV_Manager &other) = delete;
    CV_Manager &operator=(const CV_Manager &other) = delete;

    // Définit le pointeur vers le buffer de Surface
    void SetNativeWindow(ANativeWindow *native_window);

    void SetUpCamera();
    void CameraLoop();
    bool IsInitialized() const;
    void SetInitialized(bool value);
    void RunCV();
    void HaltCamera();
    void FlipCamera();
    void StopCameraLoop();
    void StartCameraLoop();
    void TearDownCamera();
    void ReleaseMats();
    void SetGpsData(double lat, double lon, double alt, float accuracy);
    void SetFrameCallback(JavaVM *jvm, jobject cb, JNIEnv *env);
    void SetTargetFps(int fps);

private:
    ANativeWindow *m_native_window;
    ANativeWindow_Buffer m_native_buffer;
    Native_Camera *m_native_camera;
    camera_type m_selected_camera_type = BACK_CAMERA; // Par défaut
    ImageFormat m_view{0, 0, 0};
    Image_Reader *m_image_reader;
    AImage *m_image;
    //volatile bool m_camera_ready;
    clock_t start_t, end_t;
    double  total_t;
    bool scan_mode;
    Mat display_mat;
    Mat frame_gray;
    Mat grad_x;
    Mat abs_grad_x;
    Mat grad_y;
    Mat abs_grad_y;
    Mat detected_edges;
    Mat thresh;
    Mat kernel;
    Mat anchor;
    Mat cleaned;
    Mat hierarchy;
    vector<vector<Point>> contours;
    Scalar CV_PURPLE = Scalar(255, 0, 255);
    Scalar CV_RED = Scalar(255, 0, 0);
    Scalar CV_GREEN = Scalar(0, 255, 0);
    Scalar CV_BLUE = Scalar(0, 0, 255);
    //bool m_camera_thread_stopped = false;
    bool m_initialized = false;
    std::thread m_camera_thread;
    std::atomic<bool> m_camera_thread_stopped{false};
    std::atomic<bool> m_camera_ready{false};
    std::mutex m_camera_mutex;

    // GPS — mis à jour depuis le thread Java, lu depuis CameraLoop
    std::mutex m_gps_mutex;
    double m_gps_lat{0.0};
    double m_gps_lon{0.0};
    double m_gps_alt{0.0};
    float  m_gps_acc{0.0f};
    std::atomic<bool> m_gps_valid{false};

    // ABR — throttling FPS côté NDK
    std::atomic<int> m_target_fps{30};
    int64_t          m_last_sent_frame_ns{0};

    JavaVM              *m_jvm{nullptr};
    jobject              m_frame_cb{nullptr};
    jmethodID            m_onframe_mid{nullptr};
    uint32_t             m_orientation{0};
    std::vector<uint8_t> m_nv21_buf;

};



#endif //CYCLOPE_CV_MANAGER_H
