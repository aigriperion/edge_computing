//
// Created by girard on 18/02/2026.
//

#ifndef EDGECOMPUTER_SOCKETTCP_H
#define EDGECOMPUTER_SOCKETTCP_H


#include <string>
#include <cstdint>
#include <opencv2/core.hpp>

class SocketClient {
public:
    SocketClient(const std::string& host, int port);
    ~SocketClient();

    bool ConnectToServer();
    void Close();

    bool SendImageDims(int width, int height);

    // Envoie une image OpenCV (pixels bruts, sans encodage)
    bool SendImage(const cv::Mat& img);

private:
    bool sendAll(const void* data, size_t len);

private:
    std::string host_;
    int port_ = 0;
    int sock_ = -1;
};

#endif //EDGECOMPUTER_SOCKETTCP_H
