//
// Created by girard on 18/02/2026.
//

#include "headers/SocketTcp.h"
#include "headers/Util.h"

#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>

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

// Protocole :
// type=1 -> dims      : [0x01][width:i32LE][height:i32LE]
// type=2 -> H.264 frame : [0x02][size:i32LE][h264_annex_b_data]
// type=3 -> H.264 config (SPS/PPS) : [0x03][size:i32LE][sps_pps_annex_b_data]

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

bool SocketClient::SendH264Config(const uint8_t* data, size_t size) {
    if (sock_ < 0) return false;

    uint8_t type = 3;
    int32_t sz = (int32_t)size;

    if (!sendAll(&type, 1)) return false;
    if (!sendAll(&sz, sizeof(sz))) return false;
    if (!sendAll(data, size)) return false;
    return true;
}

bool SocketClient::SendH264Frame(const uint8_t* data, size_t size) {
    if (sock_ < 0) return false;

    uint8_t type = 2;
    int32_t sz = (int32_t)size;

    if (!sendAll(&type, 1)) return false;
    if (!sendAll(&sz, sizeof(sz))) return false;
    if (!sendAll(data, size)) return false;
    return true;
}
