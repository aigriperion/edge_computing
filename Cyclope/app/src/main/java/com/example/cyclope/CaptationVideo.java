package com.example.cyclope;

import org.webrtc.CapturerObserver;
import org.webrtc.EglBase;
import org.webrtc.NV21Buffer;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

public class CaptationVideo {

    private final VideoSource videoSource;
    private final VideoTrack videoTrack;
    private final CapturerObserver capturerObserver;

    public CaptationVideo(PeerConnectionFactory factory, EglBase eglBase) {
        videoSource = factory.createVideoSource(false);
        capturerObserver = videoSource.getCapturerObserver();
        videoTrack = factory.createVideoTrack("v0", videoSource);
        videoTrack.setEnabled(true);
    }

    public VideoTrack getTrack() { return videoTrack; }

    // Appelé depuis le thread NDK pour chaque frame YUV
    public void onNdkFrame(byte[] nv21, int width, int height, int rotation, long timestampNs) {
        NV21Buffer buffer = new NV21Buffer(nv21, width, height, null);
        VideoFrame frame = new VideoFrame(buffer, rotation, timestampNs);
        capturerObserver.onFrameCaptured(frame);
        frame.release();
    }

    public void dispose() {
        videoTrack.dispose();
        videoSource.dispose();
    }
}