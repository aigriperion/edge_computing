//
// Created by girard on 23/02/2026.
//

#include "headers/H264Encoder.h"
#include "headers/Util.h"

#include <opencv2/imgproc.hpp>
#include <cstring>

H264Encoder::~H264Encoder() {
    Stop();
}

bool H264Encoder::Init(int width, int height, int bitrate, int fps) {
    width_ = width;
    height_ = height;
    fps_ = fps;
    basePts_ = -1;

    codec_ = AMediaCodec_createEncoderByType("video/avc");
    if (!codec_) {
        LOGE("H264Encoder: failed to create encoder");
        return false;
    }

    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21); // NV12 (COLOR_FormatYUV420SemiPlanar)
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

    media_status_t status = AMediaCodec_configure(codec_, fmt, nullptr, nullptr,
                                                  AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(fmt);

    if (status != AMEDIA_OK) {
        LOGE("H264Encoder: configure failed (%d)", status);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        LOGE("H264Encoder: start failed (%d)", status);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    LOGI("H264Encoder: OK %dx%d @ %d bps %d fps", width, height, bitrate, fps);
    return true;
}

bool H264Encoder::Encode(const cv::Mat& bgra, std::vector<H264Chunk>& out, int64_t timestampUs) {
    if (!codec_) return false;

    out.clear();

    // Resize if needed
    cv::Mat input = bgra;
    if (bgra.cols != width_ || bgra.rows != height_) {
        cv::resize(bgra, input, cv::Size(width_, height_));
    }

    // RGBA -> I420 (YUV420p)
    cv::Mat yuv_i420;
    cv::cvtColor(input, yuv_i420, cv::COLOR_BGRA2YUV_I420);

    // Prepare NV12 buffer: Y plane + interleaved UV
    int ySize = width_ * height_;
    int uvSize = ySize / 2;
    int totalSize = ySize + uvSize;

    // Dequeue input buffer
    ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 10000); // 10ms timeout
    if (inIdx < 0) {
        // No buffer available, try to drain output anyway
        DrainOutput(out);
        return true;
    }

    size_t inBufSize = 0;
    uint8_t* inBuf = AMediaCodec_getInputBuffer(codec_, inIdx, &inBufSize);
    if (!inBuf || (int)inBufSize < totalSize) {
        LOGE("H264Encoder: input buffer too small (%zu < %d)", inBufSize, totalSize);
        AMediaCodec_queueInputBuffer(codec_, inIdx, 0, 0, 0, 0);
        DrainOutput(out);
        return false;
    }

    // Copy Y plane directly
    const uint8_t* yuvData = yuv_i420.data;
    memcpy(inBuf, yuvData, ySize);

    // Interleave U and V planes (I420 -> NV12)
    const uint8_t* uPlane = yuvData + ySize;
    const uint8_t* vPlane = uPlane + ySize / 4;
    uint8_t* uvDst = inBuf + ySize;

    for (int i = 0; i < ySize / 4; i++) {
        uvDst[2 * i]     = uPlane[i];
        uvDst[2 * i + 1] = vPlane[i];
    }

    // Compute PTS from real camera timestamp
    if (basePts_ < 0) basePts_ = timestampUs;
    int64_t pts = timestampUs - basePts_;

    AMediaCodec_queueInputBuffer(codec_, inIdx, 0, totalSize, pts, 0);

    // Drain all available output
    DrainOutput(out);
    return true;
}

void H264Encoder::DrainOutput(std::vector<H264Chunk>& out) {
    AMediaCodecBufferInfo info;
    for (;;) {
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        if (outIdx < 0) break;

        if (info.size > 0) {
            size_t outBufSize = 0;
            uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec_, outIdx, &outBufSize);

            H264Chunk chunk;
            chunk.data.assign(outBuf + info.offset, outBuf + info.offset + info.size);
            chunk.isConfig = (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
            out.push_back(std::move(chunk));
        }

        AMediaCodec_releaseOutputBuffer(codec_, outIdx, false);
    }
}

void H264Encoder::Stop() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
}
