//
// Created by girard on 18/02/2026.
//

#include "headers/CV_Manager.h"

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

    // Recuperer les dimensions reelles de l'ecran (avant setBuffersGeometry)
    int32_t screenW = ANativeWindow_getWidth(m_native_window);
    int32_t screenH = ANativeWindow_getHeight(m_native_window);
    LOGI("Screen dimensions: %d x %d", screenW, screenH);

    // Determine une resolution compatible pour la capture
    m_native_camera->MatchCaptureSizeRequest(&m_view, screenW, screenH);

    ASSERT(m_view.width && m_view.height, "Could not find supportable resolution");
    LOGI("Camera capture resolution: %d x %d", m_view.width, m_view.height);

    // Buffer geometry = dimensions ecran pour remplir toute la surface
    ANativeWindow_setBuffersGeometry(m_native_window, screenW, screenH, WINDOW_FORMAT_RGBX_8888);

    m_image_reader = new Image_Reader(&m_view, AIMAGE_FORMAT_YUV_420_888);
    m_image_reader->SetPresentRotation(m_native_camera->GetOrientation());

    ANativeWindow *image_reader_window = m_image_reader->GetNativeWindow();
    m_camera_ready = m_native_camera->CreateCaptureSession(image_reader_window);
}

void CV_Manager::CameraLoop() {
    bool buffer_printout = false;
    m_camera_thread_stopped = false;

    while (!m_camera_thread_stopped) {
        if (!m_camera_ready || !m_image_reader) { continue; }
        m_image = m_image_reader->GetLatestImage();
        if (m_image == nullptr) { continue; }

        if (m_camera_thread_stopped) {
            m_image_reader->DeleteImage(m_image);
            m_image = nullptr;
            break;
        }

        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(m_native_window, &buffer, nullptr) < 0) {
            m_image_reader->DeleteImage(m_image);
            m_image = nullptr;
            break;  // lock failed = surface probably destroyed, exit loop
        }

        if (!buffer_printout) {
            buffer_printout = true;
            LOGI("/// H-W-S-F: %d, %d, %d, %d", buffer.height, buffer.width, buffer.stride, buffer.format);
        }

        m_image_reader->DisplayImage(&buffer, m_image);
        display_mat = Mat(buffer.height, buffer.stride, CV_8UC4, buffer.bits);
        //BarcodeDetect(display_mat);
        Mat send_mat;
        if (m_Client) {
            send_mat = display_mat.clone(); // copie avant unlock, buffer.bits sera invalide après
        }
        ANativeWindow_unlockAndPost(m_native_window);
        if (m_Client) {
            m_Client->SendImage(send_mat); // cvtColor + imencode + TCP hors du lock
        }
        ReleaseMats();
    }
    LOGI("CameraLoop exited cleanly");
}

void CV_Manager::BarcodeDetect(Mat &frame) {
    int ddepth = CV_16S;

    // Conversion en niveaux de gris
    cvtColor(frame, frame_gray, COLOR_RGBA2GRAY);

    // Calcul du gradient en X
    Sobel(frame_gray, grad_x, ddepth, 1, 0);
    convertScaleAbs(grad_x, abs_grad_x);
    // Calcul du gradient en Y
    Sobel(frame_gray, grad_y, ddepth, 0, 1);
    convertScaleAbs(grad_y, abs_grad_y);

    // Gradient total (approximation)
    addWeighted(abs_grad_x, 0.5, abs_grad_x, 0.5, 0, detected_edges);

    // Reduction du bruit avec un flou gaussien
    GaussianBlur(detected_edges, detected_edges, Size(3,3), 0, 0, BORDER_DEFAULT);

    // Seuillage pour reduire davantage le bruit
    threshold(detected_edges, thresh, 120, 255, THRESH_BINARY);
    threshold(thresh, thresh, 0, 255, THRESH_BINARY + THRESH_OTSU);

    // Fermeture des espaces a l'aide d'un kernel rectangulaire
    kernel = getStructuringElement(MORPH_RECT, Size(21,7));
    morphologyEx(thresh, cleaned, MORPH_CLOSE, kernel);

    // Erosion et dilatation pour affiner le resultat
    erode(cleaned, cleaned, anchor, Point(-1,-1), 4);
    dilate(cleaned, cleaned, anchor, Point(-1,-1), 4);

    // Extraction des contours
    findContours(cleaned, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

    // Tri des contours par aire croissante
    std::sort(contours.begin(), contours.end(), [](const vector<Point>& c1, const vector<Point>& c2) {
        return contourArea(c1, false) < contourArea(c2, false);
    });

    // Dessin du plus grand contour
    drawContours(frame, contours, int(contours.size()-1), CV_GREEN, 2, LINE_8, hierarchy, 0, Point());
}

void CV_Manager::RunCV() {
    scan_mode = true;
    total_t = 0;
    start_t = clock();
}

void CV_Manager::HaltCamera() {
    m_camera_thread_stopped = true;
}

void CV_Manager::FlipCamera() {
    // Reinitialisation des ressources
    if (m_image_reader != nullptr) {
        delete m_image_reader;
        m_image_reader = nullptr;
    }
    if (m_native_camera != nullptr) {
        delete m_native_camera;
        m_native_camera = nullptr;
    }

    if (m_selected_camera_type == FRONT_CAMERA) {
        m_selected_camera_type = BACK_CAMERA;
    } else {
        m_selected_camera_type = FRONT_CAMERA;
    }

    SetUpCamera();
}
void CV_Manager::SetUpTCP()
{

    const char hostname[] = "172.16.81.179";
    int port = 9999;

    SocketClient* client =new SocketClient(hostname, port);

    client->ConnectToServer();
    client->SendImageDims(640, 480);
    setSocketClient(client);

}
void CV_Manager::setSocketClient(SocketClient *client)
{
    this->m_Client = client;
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
