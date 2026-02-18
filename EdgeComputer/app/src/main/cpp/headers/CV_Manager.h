//
// Created by girard on 18/02/2026.
//

#ifndef EDGECOMPUTER_CV_MANAGER_H
#define EDGECOMPUTER_CV_MANAGER_H

#include <android/native_window.h>
#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "Image_Reader.h"
#include "Native_Camera.h"
#include "Util.h"
#include "SocketTcp.h"
#include <cstdlib>
#include <string>
#include <vector>
#include <thread>

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
    void BarcodeDetect(Mat &frame);
    void RunCV();
    void SetUpTCP();
    void setSocketClient(SocketClient *client);
    void HaltCamera();
    void FlipCamera();
    void ReleaseMats();

private:
    ANativeWindow *m_native_window;
    ANativeWindow_Buffer m_native_buffer;
    Native_Camera *m_native_camera;
    camera_type m_selected_camera_type = BACK_CAMERA; // Par défaut
    ImageFormat m_view{0, 0, 0};
    Image_Reader *m_image_reader;
    AImage *m_image;
    volatile bool m_camera_ready;
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
    atomic_bool m_camera_thread_stopped{true};
    SocketClient*     m_Client;
    thread m_loopThread;
};

#endif //EDGECOMPUTER_CV_MANAGER_H
