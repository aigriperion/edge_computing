//
// Created by girard on 23/02/2026.
//

#ifndef EDGECOMPUTER_H264ENCODER_H
#define EDGECOMPUTER_H264ENCODER_H

#include <cstdint>
#include <vector>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <opencv2/core.hpp>

struct H264Chunk {
    std::vector<uint8_t> data;
    bool isConfig;   // true = SPS/PPS, false = frame data
};

class H264Encoder {
public:
    H264Encoder() = default;
    ~H264Encoder();

    bool Init(int width, int height, int bitrate, int fps);
    bool Encode(const cv::Mat& rgba, std::vector<H264Chunk>& out);
    void Stop();

private:
    void DrainOutput(std::vector<H264Chunk>& out);

    AMediaCodec* codec_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    int fps_ = 30;
    int64_t frameIndex_ = 0;
};

#endif //EDGECOMPUTER_H264ENCODER_H
