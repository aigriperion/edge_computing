//
// Created by ladiaviakoye on 22/03/2026.
//
#include "CV_Manager.h"
#include <opencv2/imgproc.hpp>
#include <sstream>
#include <iomanip>

using namespace std;
using namespace cv;

CV_Manager::CV_Manager()
        : m_camera_ready(false), m_image(nullptr), m_image_reader(nullptr),
          m_native_camera(nullptr) {
}

CV_Manager::~CV_Manager() {
    if (m_native_camera != nullptr) {
        delete m_native_camera;
        m_native_camera = nullptr;
    }
    if (m_native_window != nullptr) {
        ANativeWindow_release(m_native_window);
        m_native_window = nullptr;
    }
    if (m_image_reader != nullptr) {
        delete m_image_reader;
        m_image_reader = nullptr;
    }
}

void CV_Manager::SetNativeWindow(ANativeWindow *native_window) {
    m_native_window = native_window;
}

void CV_Manager::SetUpCamera() {
    m_native_camera = new Native_Camera(m_selected_camera_type);

    int nativeWidth = 1280, nativeHeight = 720;
    if (m_native_window != nullptr) {
        nativeWidth  = ANativeWindow_getWidth(m_native_window);
        nativeHeight = ANativeWindow_getHeight(m_native_window);
        LOGI("[CV_Manager] Native window: %dx%d", nativeWidth, nativeHeight);
        ASSERT(nativeWidth > 0 && nativeHeight > 0, "[CV_Manager] Invalid native window size");
        ANativeWindow_setBuffersGeometry(m_native_window, nativeWidth, nativeHeight, WINDOW_FORMAT_RGBX_8888);
    } else {
        LOGI("[CV_Manager] Headless mode: target %dx%d", nativeWidth, nativeHeight);
    }

    // Choix d'une résolution caméra (proche du ratio cible)
    m_native_camera->MatchCaptureSizeRequest(&m_view, nativeWidth, nativeHeight);
    ASSERT(m_view.width && m_view.height, "[CV_Manager] Could not find supportable capture resolution");
    LOGI("[CV_Manager] Capture size (YUV): %dx%d format=%d", m_view.width, m_view.height, m_view.format);

    m_image_reader = new Image_Reader(&m_view, AIMAGE_FORMAT_YUV_420_888);

    const int orientation = m_native_camera->GetOrientation();
    m_image_reader->SetPresentRotation(orientation);
    m_orientation = orientation;
    LOGI("[CV_Manager] Present rotation: %d", orientation);

    ANativeWindow *image_reader_window = m_image_reader->GetNativeWindow();
    m_camera_ready = m_native_camera->CreateCaptureSession(image_reader_window);
    LOGI("[CV_Manager] Camera session ready: %s", m_camera_ready ? "true" : "false");
}
void CV_Manager::StartCameraLoop() {
    m_camera_thread_stopped = false;

    if (m_camera_thread.joinable()) {
        m_camera_thread.join();
    }

    m_camera_thread = std::thread(&CV_Manager::CameraLoop, this);
}
void CV_Manager::StopCameraLoop() {
    m_camera_thread_stopped = true;
    m_camera_ready = false;

    if (m_camera_thread.joinable()) {
        m_camera_thread.join();
    }
}
void CV_Manager::TearDownCamera() {
    StopCameraLoop();

    if (m_image_reader != nullptr) {
        delete m_image_reader;
        m_image_reader = nullptr;
    }

    if (m_native_camera != nullptr) {
        delete m_native_camera;
        m_native_camera = nullptr;
    }
}

void CV_Manager::CameraLoop() {
    bool buffer_printout = false;

    JNIEnv *jni_env = nullptr;
    bool thread_attached = false;
    if (m_jvm != nullptr) {
        thread_attached = (m_jvm->AttachCurrentThread(&jni_env, nullptr) == JNI_OK);
    }

    while (!m_camera_thread_stopped) {
        if (!m_camera_ready || m_image_reader == nullptr) {
            usleep(1000);
            continue;
        }

        AImage* image = m_image_reader->GetLatestImage();
        if (image == nullptr) {
            usleep(1000);
            continue;
        }

        // Feed frame to WebRTC — avec throttling ABR
        if (thread_attached && jni_env && m_frame_cb && m_onframe_mid) {
            auto nowNs = (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
            int64_t minIntervalNs = 1'000'000'000LL / m_target_fps.load();

            if (nowNs - m_last_sent_frame_ns >= minIntervalNs) {
                m_last_sent_frame_ns = nowNs;
                m_image_reader->ExtractNV21(image, m_nv21_buf);
                if (!m_nv21_buf.empty()) {
                    jbyteArray jbuf = jni_env->NewByteArray((jsize)m_nv21_buf.size());
                    jni_env->SetByteArrayRegion(jbuf, 0, (jsize)m_nv21_buf.size(),
                                                reinterpret_cast<const jbyte *>(m_nv21_buf.data()));
                    jni_env->CallVoidMethod(m_frame_cb, m_onframe_mid,
                                           jbuf,
                                           (jint)m_view.width, (jint)m_view.height,
                                           (jint)m_orientation, (jlong)nowNs);
                    jni_env->DeleteLocalRef(jbuf);
                }
            }
            // Si frame skippée, l'affichage local continue normalement ci-dessous
        }

        if (m_native_window != nullptr) {
            ANativeWindow_acquire(m_native_window);

            ANativeWindow_Buffer buffer;
            if (ANativeWindow_lock(m_native_window, &buffer, nullptr) < 0) {
                ANativeWindow_release(m_native_window);
                m_image_reader->DeleteImage(image);
                continue;
            }

            if (!buffer_printout) {
                buffer_printout = true;
                LOGI("/// H-W-S-F: %d, %d, %d, %d", buffer.height, buffer.width, buffer.stride, buffer.format);
            }

            m_image_reader->DisplayImage(&buffer, image);

            display_mat = Mat(buffer.height, buffer.width, CV_8UC4, buffer.bits, buffer.stride * 4);

            if (m_gps_valid) {
                double lat, lon, alt;
                float acc;
                {
                    std::lock_guard<std::mutex> lock(m_gps_mutex);
                    lat = m_gps_lat; lon = m_gps_lon; alt = m_gps_alt; acc = m_gps_acc;
                }
                std::ostringstream oss;
                oss << std::fixed << std::setprecision(6) << "Lat: " << lat << "  Lon: " << lon;
                std::string line1 = oss.str();
                oss.str("");
                oss << std::fixed << std::setprecision(1) << "Alt: " << alt << " m  Acc: " << acc << " m";
                std::string line2 = oss.str();

                double fontScale = display_mat.rows / 720.0;
                int thickness = std::max(1, (int)(fontScale * 2));
                int baseline = 0;
                Size sz = getTextSize(line1, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline);
                int margin = 16;
                int lineH = sz.height + baseline + 8;

                Rect bg(margin - 4, margin - sz.height - 4,
                        std::max(
                            (int)getTextSize(line1, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline).width,
                            (int)getTextSize(line2, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline).width
                        ) + 8, lineH * 2 + 8);
                bg &= Rect(0, 0, display_mat.cols, display_mat.rows);
                display_mat(bg) *= 0.4;

                Scalar white(255, 255, 255, 255);
                putText(display_mat, line1, Point(margin, margin + lineH * 0),
                        FONT_HERSHEY_SIMPLEX, fontScale, Scalar(0,0,0,255), thickness + 2);
                putText(display_mat, line1, Point(margin, margin + lineH * 0),
                        FONT_HERSHEY_SIMPLEX, fontScale, white, thickness);
                putText(display_mat, line2, Point(margin, margin + lineH * 1),
                        FONT_HERSHEY_SIMPLEX, fontScale, Scalar(0,0,0,255), thickness + 2);
                putText(display_mat, line2, Point(margin, margin + lineH * 1),
                        FONT_HERSHEY_SIMPLEX, fontScale, white, thickness);
            }

            ANativeWindow_unlockAndPost(m_native_window);
            ANativeWindow_release(m_native_window);
        } else {
            // Mode agent sans affichage
            m_image_reader->DeleteImage(image);
        }

        ReleaseMats();
    }

    LOGI("CameraLoop stopped cleanly");

    if (thread_attached && m_jvm) {
        m_jvm->DetachCurrentThread();
    }
}

bool CV_Manager::IsInitialized() const {
    return m_initialized;
}

void CV_Manager::SetInitialized(bool value) {
    m_initialized = value;
}

void CV_Manager::RunCV() {
    scan_mode = true;
    total_t = 0;
    start_t = clock();
}

void CV_Manager::HaltCamera() {
    if (m_native_camera == nullptr) {
        LOGE("Can't flip camera without camera instance");
        return;
    } else if (m_native_camera->GetCameraCount() < 2) {
        LOGE("Only one camera is available");
        return;
    }
    m_camera_thread_stopped = true;
}

void CV_Manager::FlipCamera() {
    std::lock_guard<std::mutex> lock(m_camera_mutex);

    if (m_native_camera == nullptr) {
        LOGE("FlipCamera: camera not initialized");
        return;
    }

    if (m_native_camera->GetCameraCount() < 2) {
        LOGE("FlipCamera: only one camera available");
        return;
    }

    LOGI("FlipCamera: stopping current camera");

    TearDownCamera();

    if (m_selected_camera_type == FRONT_CAMERA) {
        m_selected_camera_type = BACK_CAMERA;
    } else {
        m_selected_camera_type = FRONT_CAMERA;
    }

    LOGI("FlipCamera: starting new camera");

    SetUpCamera();
    StartCameraLoop();
}

void CV_Manager::SetGpsData(double lat, double lon, double alt, float accuracy) {
    std::lock_guard<std::mutex> lock(m_gps_mutex);
    m_gps_lat = lat;
    m_gps_lon = lon;
    m_gps_alt = alt;
    m_gps_acc = accuracy;
    m_gps_valid = true;
}

void CV_Manager::ReleaseMats() {
    display_mat.release();
    frame_gray.release();
    grad_x.release();
    abs_grad_x.release();
    grad_y.release();
    abs_grad_y.release();
    detected_edges.release();
    thresh.release();
    kernel.release();
    anchor.release();
    cleaned.release();
    hierarchy.release();
}

void CV_Manager::SetTargetFps(int fps) {
    if (fps > 0 && fps <= 60) {
        m_target_fps.store(fps);
        LOGI("[CV_Manager] Target FPS → %d", fps);
    }
}

void CV_Manager::SetFrameCallback(JavaVM *jvm, jobject cb, JNIEnv *env) {
    std::lock_guard<std::mutex> lock(m_camera_mutex);
    if (m_frame_cb != nullptr && m_jvm != nullptr) {
        JNIEnv *e = nullptr;
        if (m_jvm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) == JNI_OK && e) {
            e->DeleteGlobalRef(m_frame_cb);
        }
    }
    m_jvm = jvm;
    m_frame_cb = cb;
    if (cb != nullptr && env != nullptr) {
        jclass cls = env->GetObjectClass(cb);
        m_onframe_mid = env->GetMethodID(cls, "onNdkFrame", "([BIIIJ)V");
        env->DeleteLocalRef(cls);
    }
}
