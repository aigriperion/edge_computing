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

    const int nativeWidth  = ANativeWindow_getWidth(m_native_window);
    const int nativeHeight = ANativeWindow_getHeight(m_native_window);

    LOGI("[CV_Manager] Native window: %dx%d", nativeWidth, nativeHeight);
    ASSERT(nativeWidth > 0 && nativeHeight > 0, "[CV_Manager] Invalid native window size");

    // Choix d'une résolution caméra (proche du ratio écran)
    m_native_camera->MatchCaptureSizeRequest(&m_view, nativeWidth, nativeHeight);
    ASSERT(m_view.width && m_view.height, "[CV_Manager] Could not find supportable capture resolution");

    LOGI("[CV_Manager] Capture size (YUV): %dx%d format=%d", m_view.width, m_view.height, m_view.format);

    // Buffers d'affichage = taille écran (plein écran)
    ANativeWindow_setBuffersGeometry(
            m_native_window,
            nativeWidth,
            nativeHeight,
            WINDOW_FORMAT_RGBX_8888
    );

    m_image_reader = new Image_Reader(&m_view, AIMAGE_FORMAT_YUV_420_888);

    const int orientation = m_native_camera->GetOrientation();
    m_image_reader->SetPresentRotation(orientation);
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

    while (!m_camera_thread_stopped) {
        if (!m_camera_ready || m_image_reader == nullptr || m_native_window == nullptr) {
            usleep(1000);
            continue;
        }

        AImage* image = m_image_reader->GetLatestImage();
        if (image == nullptr) {
            usleep(1000);
            continue;
        }

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

        display_mat = Mat(
                buffer.height,
                buffer.width,
                CV_8UC4,
                buffer.bits,
                buffer.stride * 4
        );

        if (m_gps_valid) {
            double lat, lon, alt;
            float acc;
            {
                std::lock_guard<std::mutex> lock(m_gps_mutex);
                lat = m_gps_lat;
                lon = m_gps_lon;
                alt = m_gps_alt;
                acc = m_gps_acc;
            }
            std::ostringstream oss;
            oss << std::fixed << std::setprecision(6)
                << "Lat: " << lat << "  Lon: " << lon;
            std::string line1 = oss.str();

            oss.str("");
            oss << std::fixed << std::setprecision(1)
                << "Alt: " << alt << " m  Acc: " << acc << " m";
            std::string line2 = oss.str();

            double fontScale = display_mat.rows / 720.0;
            int thickness = std::max(1, (int)(fontScale * 2));
            int baseline = 0;
            Size sz = getTextSize(line1, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline);
            int margin = 16;
            int lineH = sz.height + baseline + 8;

            // Fond semi-transparent noir
            Rect bg(margin - 4, margin - sz.height - 4,
                    std::max(
                        (int)getTextSize(line1, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline).width,
                        (int)getTextSize(line2, FONT_HERSHEY_SIMPLEX, fontScale, thickness, &baseline).width
                    ) + 8,
                    lineH * 2 + 8);
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

        ReleaseMats();
    }

    LOGI("CameraLoop stopped cleanly");
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
