//
// Created by girard on 18/02/2026.
//

#include "headers/SocketTcp.h"
#include "headers/Util.h"

#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
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

    int flag = 1;
    setsockopt(sock_, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

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
// [0xED][0x9E][type:1B][size:4B LE][payload:size B]
// type=1 -> dims      : payload = [width:4B][height:4B], size = 8
// type=2 -> H.264 frame : payload = h264_annex_b_data
// type=3 -> H.264 config (SPS/PPS) : payload = sps_pps_annex_b_data

bool SocketClient::sendMessage(uint8_t type, const void* payload, size_t size) {
    if (sock_ < 0) return false;

    uint8_t header[7];
    header[0] = TCP_MAGIC_0;
    header[1] = TCP_MAGIC_1;
    header[2] = type;
    uint32_t sz = (uint32_t)size;
    memcpy(&header[3], &sz, sizeof(sz));

    if (!sendAll(header, sizeof(header))) {
        LOGE("sendMessage: header send failed (type=%d)", type);
        return false;
    }
    if (size > 0 && !sendAll(payload, size)) {
        LOGE("sendMessage: payload send failed (type=%d, size=%zu)", type, size);
        return false;
    }
    return true;
}

bool SocketClient::SendImageDims(int width, int height) {
    int32_t dims[2] = { width, height };
    return sendMessage(1, dims, sizeof(dims));
}

bool SocketClient::SendH264Config(const uint8_t* data, size_t size) {
    return sendMessage(3, data, size);
}

bool SocketClient::SendH264Frame(const uint8_t* data, size_t size) {
    return sendMessage(2, data, size);
}
