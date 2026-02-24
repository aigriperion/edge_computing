//
// Created by girard on 18/02/2026.
//

#ifndef EDGECOMPUTER_SOCKETTCP_H
#define EDGECOMPUTER_SOCKETTCP_H


#include <string>
#include <cstdint>

// Magic bytes pour le protocole TCP
static constexpr uint8_t TCP_MAGIC_0 = 0xED;
static constexpr uint8_t TCP_MAGIC_1 = 0x9E;

class SocketClient {
public:
    SocketClient(const std::string& host, int port);
    ~SocketClient();

    bool ConnectToServer();
    void Close();

    bool SendImageDims(int width, int height);

    // Envoie le SPS/PPS (config H.264) - type 3
    bool SendH264Config(const uint8_t* data, size_t size);

    // Envoie une frame H.264 encodee - type 2
    bool SendH264Frame(const uint8_t* data, size_t size);

private:
    bool sendAll(const void* data, size_t len);
    bool sendMessage(uint8_t type, const void* payload, size_t size);

private:
    std::string host_;
    int port_ = 0;
    int sock_ = -1;
};

#endif //EDGECOMPUTER_SOCKETTCP_H
