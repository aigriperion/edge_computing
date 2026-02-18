//
// Created by girard on 18/02/2026.
//

#include "headers/SocketTcp.h"
#include "headers/Util.h"

#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>

#include <vector>
#include <cstring>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>

SocketClient::SocketClient(const std::string& host, int port)
        : host_(host), port_(port) {}

SocketClient::~SocketClient() {
    Close();
}

void SocketClient::Close() {
    if (sock_ >= 0) {
        close(sock_);
        sock_ = -1;
    }
}

bool SocketClient::ConnectToServer() {
    Close();

    sock_ = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_ < 0) {
        LOGE("socket() failed errno=%d", errno);
        return false;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port_);

    if (inet_pton(AF_INET, host_.c_str(), &addr.sin_addr) != 1) {
        LOGE("inet_pton failed for %s", host_.c_str());
        Close();
        return false;
    }

    if (connect(sock_, (sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("connect() failed errno=%d", errno);
        Close();
        return false;
    }

    LOGI("Connected to %s:%d", host_.c_str(), port_);
    return true;
}

bool SocketClient::sendAll(const void* data, size_t len) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(data);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(sock_, p + sent, len - sent, 0);
        if (n <= 0) {
            LOGE("send() failed errno=%d", errno);
            return false;
        }
        sent += (size_t)n;
    }
    return true;
}

// Petit protocole : on envoie un header 1 octet type + payload
// type=1 -> dims (int32 w, int32 h)
// type=2 -> jpeg (int32 size + bytes)
bool SocketClient::SendImageDims(int width, int height) {
    if (sock_ < 0) return false;

    uint8_t type = 1;
    int32_t w = width;
    int32_t h = height;

    if (!sendAll(&type, 1)) return false;
    if (!sendAll(&w, sizeof(w))) return false;
    if (!sendAll(&h, sizeof(h))) return false;
    return true;
}

bool SocketClient::SendImage(const cv::Mat& img) {
    if (sock_ < 0) return false;
    if (img.empty()) return false;

    cv::Mat bgr;

    // Tes frames dans CV_Manager sont souvent en CV_8UC4 (RGBA)
    if (img.type() == CV_8UC4) {
        cv::cvtColor(img, bgr, cv::COLOR_RGBA2BGR);
    } else if (img.type() == CV_8UC3) {
        bgr = img;
    } else {
        LOGE("SendImage: unsupported mat type=%d", img.type());
        return false;
    }

    std::vector<uchar> jpeg;
    std::vector<int> params = { cv::IMWRITE_JPEG_QUALITY, 80 };
    if (!cv::imencode(".jpg", bgr, jpeg, params)) {
        LOGE("imencode jpg failed");
        return false;
    }

    uint8_t type = 2;
    int32_t size = (int32_t)jpeg.size();

    if (!sendAll(&type, 1)) return false;
    if (!sendAll(&size, sizeof(size))) return false;
    if (!sendAll(jpeg.data(), jpeg.size())) return false;

    return true;
}