//
// Created by ladiaviakoye on 22/03/2026.
//

#include "Native_Camera.h"
#include <__algorithm/max.h>
#include <cmath>

Native_Camera::Native_Camera(camera_type type) {

    ACameraMetadata *cameraMetadata = nullptr;
    camera_status_t cameraStatus = ACAMERA_OK;

    m_camera_manager = ACameraManager_create();

    cameraStatus =
            ACameraManager_getCameraIdList(m_camera_manager, &m_camera_id_list);
    ASSERT(cameraStatus == ACAMERA_OK,
           "Failed to get camera id list (reason: %d)", cameraStatus);
    ASSERT(m_camera_id_list->numCameras > 0, "No camera device detected");

    // ASSUMPTION: Back camera is index[0] and front is index[1]
    // TODO - why I need orientation as is below
    if (type == BACK_CAMERA) {
        m_selected_camera_id = m_camera_id_list->cameraIds[0];
        m_camera_orientation = 90;
    } else {
        ASSERT(m_camera_id_list->numCameras > 1, "No dual camera setup");
        m_selected_camera_id = m_camera_id_list->cameraIds[1];
        m_camera_orientation = 270;
    }

    cameraStatus = ACameraManager_getCameraCharacteristics(
            m_camera_manager, m_selected_camera_id, &cameraMetadata);
    ASSERT(cameraStatus == ACAMERA_OK, "Failed to get camera meta data of ID: %s",
           m_selected_camera_id);

    m_device_state_callbacks.onDisconnected = CameraDeviceOnDisconnected;
    m_device_state_callbacks.onError = CameraDeviceOnError;

    cameraStatus =
            ACameraManager_openCamera(m_camera_manager, m_selected_camera_id,
                                      &m_device_state_callbacks, &m_camera_device);
    ASSERT(cameraStatus == ACAMERA_OK, "Failed to open camera device (id: %s)",
           m_selected_camera_id);

//  ACameraMetadata_const_entry entry;
//  ACameraMetadata_getConstEntry(
//      cameraMetadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &entry);

    m_camera_ready = true;
}

Native_Camera::~Native_Camera() {
    if (m_capture_session != nullptr) {
        ACameraCaptureSession_stopRepeating(m_capture_session);
        ACameraCaptureSession_abortCaptures(m_capture_session);
        ACameraCaptureSession_close(m_capture_session);
        m_capture_session = nullptr;
    }

    if (m_capture_request != nullptr) {
        ACaptureRequest_free(m_capture_request);
        m_capture_request = nullptr;
    }

    if (m_camera_output_target != nullptr) {
        ACameraOutputTarget_free(m_camera_output_target);
        m_camera_output_target = nullptr;
    }

    if (m_session_output != nullptr && m_capture_session_output_container != nullptr) {
        ACaptureSessionOutputContainer_remove(m_capture_session_output_container, m_session_output);
    }

    if (m_session_output != nullptr) {
        ACaptureSessionOutput_free(m_session_output);
        m_session_output = nullptr;
    }

    if (m_capture_session_output_container != nullptr) {
        ACaptureSessionOutputContainer_free(m_capture_session_output_container);
        m_capture_session_output_container = nullptr;
    }

    if (m_camera_device != nullptr) {
        ACameraDevice_close(m_camera_device);
        m_camera_device = nullptr;
    }

    if (m_camera_id_list != nullptr) {
        ACameraManager_deleteCameraIdList(m_camera_id_list);
        m_camera_id_list = nullptr;
    }

    if (m_camera_manager != nullptr) {
        ACameraManager_delete(m_camera_manager);
        m_camera_manager = nullptr;
    }
}

bool Native_Camera::MatchCaptureSizeRequest(ImageFormat *resView, int32_t width, int32_t height) {
    Display_Dimension disp(width, height);
    if (m_camera_orientation == 90 || m_camera_orientation == 270) {
        disp.Flip();
    }

    const double targetRatio = disp.width() / (double)disp.height();
    LOGI("[Native_Camera] Target display ratio=%.4f (%dx%d)", targetRatio, disp.width(), disp.height());

    ACameraMetadata *metadata;
    ACameraManager_getCameraCharacteristics(m_camera_manager, m_selected_camera_id, &metadata);

    ACameraMetadata_const_entry entry;
    ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry);

    bool foundIt = false;

    // “best” criteria
    double bestScore = 1e9;
    int32_t bestW = 0, bestH = 0;

    auto scoreCandidate = [&](int w, int h) -> double {
        double r = w / (double)h;
        double ratioDiff = fabs(r - targetRatio);

        // pénalise les tailles trop grandes (coût CPU + copie + OpenCV)
        int longSide = std::max(w, h);
        double sizePenalty = 0.0;
        if (longSide > 1920) sizePenalty = (longSide - 1920) / 1920.0; // pénalité douce

        return ratioDiff * 10.0 + sizePenalty; // ratio prioritaire
    };

    for (int i = 0; i < entry.count; ++i) {
        const int32_t format = entry.data.i32[i * 4 + 0];
        const int32_t w      = entry.data.i32[i * 4 + 1];
        const int32_t h      = entry.data.i32[i * 4 + 2];
        const int32_t input  = entry.data.i32[i * 4 + 3];
        if (input) continue;

        if (format != AIMAGE_FORMAT_YUV_420_888) continue;

        Display_Dimension res(w, h);
        // res est dans l'orientation capteur; Display_Dimension neutralise portrait/landscape
        const double s = scoreCandidate(res.width(), res.height());

        if (s < bestScore) {
            bestScore = s;
            bestW = w;
            bestH = h;
            foundIt = true;
        }
    }

    if (foundIt) {
        resView->width  = bestW;
        resView->height = bestH;
        resView->format = AIMAGE_FORMAT_YUV_420_888;
        LOGI("[Native_Camera] Selected capture: %dx%d score=%.6f", bestW, bestH, bestScore);
        return true;
    }

    // fallback (rare)
    if (disp.IsPortrait()) {
        resView->width = 720;
        resView->height = 1280;
    } else {
        resView->width = 1280;
        resView->height = 720;
    }
    resView->format = AIMAGE_FORMAT_YUV_420_888;
    LOGE("[Native_Camera] Fallback capture: %dx%d", resView->width, resView->height);
    return false;
}

bool Native_Camera::CreateCaptureSession(ANativeWindow *window) {

    camera_status_t cameraStatus = ACAMERA_OK;

    ACaptureSessionOutputContainer_create(&m_capture_session_output_container);
    ANativeWindow_acquire(window);
    ACaptureSessionOutput_create(window, &m_session_output);
    ACaptureSessionOutputContainer_add(m_capture_session_output_container,
                                       m_session_output);
    ACameraOutputTarget_create(window, &m_camera_output_target);

    // TEMPLATE_RECORD because rather have post-processing quality for more
    // accureate CV algo
    // Frame rate should be good since all image buffers are being done from
    // native side
    cameraStatus = ACameraDevice_createCaptureRequest(m_camera_device,
                                                      TEMPLATE_RECORD, &m_capture_request);
    ASSERT(cameraStatus == ACAMERA_OK,
           "Failed to create preview capture request (id: %s)",
           m_selected_camera_id);

    ACaptureRequest_addTarget(m_capture_request, m_camera_output_target);

    m_capture_session_state_callbacks.onReady = CaptureSessionOnReady;
    m_capture_session_state_callbacks.onActive = CaptureSessionOnActive;
    ACameraDevice_createCaptureSession(
            m_camera_device, m_capture_session_output_container,
            &m_capture_session_state_callbacks, &m_capture_session);

    ACameraCaptureSession_setRepeatingRequest(m_capture_session, nullptr, 1,
                                              &m_capture_request, nullptr);

    return true;
}