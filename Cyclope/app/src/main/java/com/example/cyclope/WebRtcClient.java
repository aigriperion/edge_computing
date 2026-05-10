package com.example.cyclope;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CapturerObserver;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.NV21Buffer;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcClient {

    private static final String TAG = "WebRtcClient";

    public static String SERVER_URL = "ws://192.168.1.115:3000";

    private static final List<PeerConnection.IceServer> ICE_SERVERS = Arrays.asList(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    );

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private DataChannel dataChannel;
    private CapturerObserver capturerObserver;
    private SignalingClient signalingClient;
    private Runnable flipListener;
    private String deviceId;
    private String deviceName;

    public interface FlipListener { void onFlip(); }

    public void setFlipListener(Runnable listener) { this.flipListener = listener; }

    public WebRtcClient(Context context, String deviceId, String deviceName) {
        this.context = context;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
    }

    public void init() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());

        eglBase = EglBase.create();

        factory = PeerConnectionFactory.builder()
                .setOptions(new PeerConnectionFactory.Options())
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        videoSource = factory.createVideoSource(false);
        capturerObserver = videoSource.getCapturerObserver();
        localVideoTrack = factory.createVideoTrack("v0", videoSource);
        localVideoTrack.setEnabled(true);

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("a0", audioSource);
        localAudioTrack.setEnabled(true);

        signalingClient = new SignalingClient(new SignalingClient.Listener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Signalisation connectée");
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("type", "register");
                    reg.put("role", "android");
                    reg.put("id", deviceId);
                    reg.put("name", deviceName);
                    signalingClient.send(reg.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "register", e);
                }
            }

            @Override
            public void onMessage(String message) {
                handleSignaling(message);
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "Signalisation déconnectée");
            }
        });

        signalingClient.connect(SERVER_URL);
    }

    // Appelé depuis le thread NDK (CameraLoop) pour chaque frame YUV
    public void onNdkFrame(byte[] nv21, int width, int height, int rotation, long timestampNs) {
        if (capturerObserver == null) return;
        NV21Buffer buffer = new NV21Buffer(nv21, width, height, null);
        VideoFrame frame = new VideoFrame(buffer, rotation, timestampNs);
        capturerObserver.onFrameCaptured(frame);
        frame.release();
    }

    // Envoie les données GPS via le DataChannel
    public void sendGps(double lat, double lon, double alt, float acc) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return;
        try {
            JSONObject json = new JSONObject();
            json.put("lat", lat);
            json.put("lon", lon);
            json.put("alt", alt);
            json.put("acc", acc);
            json.put("ts", System.currentTimeMillis());
            byte[] bytes = json.toString().getBytes();
            dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
        } catch (JSONException e) {
            Log.e(TAG, "sendGps error", e);
        }
    }

    private void handleSignaling(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.getString("type");
            switch (type) {
                case "registered":
                    Log.i(TAG, "Enregistré, création de l'offre...");
                    executor.execute(this::createOfferAndPeerConnection);
                    break;
                case "answer":
                    if (peerConnection != null) {
                        peerConnection.setRemoteDescription(new NoopSdpObserver(),
                                new SessionDescription(SessionDescription.Type.ANSWER,
                                        msg.getString("sdp")));
                    }
                    break;
                case "ice-candidate":
                    if (peerConnection != null) {
                        JSONObject c = msg.getJSONObject("candidate");
                        peerConnection.addIceCandidate(new IceCandidate(
                                c.getString("sdpMid"),
                                c.getInt("sdpMLineIndex"),
                                c.getString("candidate")));
                    }
                    break;
                case "request-offer":
                    Log.i(TAG, "Nouvelle offre demandée");
                    executor.execute(() -> {
                        if (peerConnection != null) {
                            peerConnection.close();
                            peerConnection.dispose();
                            peerConnection = null;
                        }
                        createOfferAndPeerConnection();
                    });
                    break;
                case "flip-camera":
                    if (flipListener != null) flipListener.run();
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "handleSignaling: " + raw, e);
        }
    }

    private void createOfferAndPeerConnection() {
        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(ICE_SERVERS);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "ice-candidate");
                    JSONObject c = new JSONObject();
                    c.put("sdpMid", candidate.sdpMid);
                    c.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    c.put("candidate", candidate.sdp);
                    json.put("candidate", c);
                    signalingClient.send(json.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onIceCandidate", e);
                }
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                Log.i(TAG, "PeerConnection: " + state);
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.i(TAG, "ICE: " + s);
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
        });

        if (peerConnection == null) {
            Log.e(TAG, "Impossible de créer PeerConnection");
            return;
        }

        peerConnection.addTrack(localVideoTrack, Collections.singletonList("s0"));
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("s0"));

        DataChannel.Init dcInit = new DataChannel.Init();
        dcInit.ordered = true;
        dataChannel = peerConnection.createDataChannel("telemetry", dcInit);

        peerConnection.createOffer(new NoopSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new NoopSdpObserver(), sdp);
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "offer");
                    json.put("sdp", sdp.description);
                    signalingClient.send(json.toString());
                    Log.i(TAG, "Offre envoyée");
                } catch (JSONException e) {
                    Log.e(TAG, "createOffer", e);
                }
            }
        }, new MediaConstraints());
    }

    private void sendJson(JSONObject base, String key, String value) {
        try {
            base.put("type", key.equals("register") ? "register" : key);
            if (key.equals("register")) base.put("role", value);
            signalingClient.send(base.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendJson", e);
        }
    }

    public void release() {
        executor.execute(() -> {
            if (dataChannel != null) { dataChannel.close(); dataChannel.dispose(); }
            if (peerConnection != null) { peerConnection.close(); peerConnection.dispose(); }
            if (localVideoTrack != null) localVideoTrack.dispose();
            if (localAudioTrack != null) localAudioTrack.dispose();
            if (videoSource != null) videoSource.dispose();
            if (audioSource != null) audioSource.dispose();
            if (factory != null) factory.dispose();
            if (eglBase != null) eglBase.release();
            if (signalingClient != null) signalingClient.close();
        });
        executor.shutdown();
    }

    private static class NoopSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { Log.e(TAG, "SDP create: " + error); }
        @Override public void onSetFailure(String error) { Log.e(TAG, "SDP set: " + error); }
    }
}