package com.example.cyclope;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WebRtcClient {

    private static final String TAG = "WebRtcClient";

    private static final List<PeerConnection.IceServer> ICE_SERVERS = Arrays.asList(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    );

    // Appelé quand un observateur demande/arrête le flux caméra
    public interface CaptureListener {
        void onStartCapture();
        void onStopCapture();
    }

    private final Context context;
    private final String deviceId;
    private final String deviceName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor();

    private final AtomicInteger frameCount = new AtomicInteger(0);
    private volatile int lastFrameWidth  = 0;
    private volatile int lastFrameHeight = 0;
    private ScheduledFuture<?> statsFuture;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private SignalingClient signalingClient;

    private CaptationVideo captationVideo;
    private CaptationSon captationSon;
    private CaptureListener captureListener;
    private Runnable flipListener;

    // Uniquement accédé depuis l'executor → pas besoin de volatile
    private boolean offerInProgress = false;

    public WebRtcClient(Context context, String deviceId, String deviceName) {
        this.context = context;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
    }

    public void setCaptureListener(CaptureListener listener) { this.captureListener = listener; }
    public void setFlipListener(Runnable listener)           { this.flipListener = listener; }

    // ── Initialisation ──────────────────────────────────────────────────────────

    public void init(String url) {
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

        captationVideo = new CaptationVideo(factory, eglBase);
        captationSon   = new CaptationSon(factory);

        signalingClient = new SignalingClient(new SignalingClient.Listener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Signalisation connectée");
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("type", "register");
                    reg.put("role", "android");
                    reg.put("id",   deviceId);
                    reg.put("name", deviceName);
                    signalingClient.send(reg.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "register", e);
                }
                sendPendingCrashReport();
            }

            @Override public void onMessage(String message) { handleSignaling(message); }
            @Override public void onDisconnected() { Log.w(TAG, "Signalisation déconnectée"); }
        });

        signalingClient.connect(url);
    }

    // ── Bridge NDK → CaptationVideo ────────────────────────────────────────────

    // Appelé depuis le thread NDK pour chaque frame caméra
    public void onNdkFrame(byte[] nv21, int width, int height, int rotation, long timestampNs) {
        if (captationVideo != null) {
            captationVideo.onNdkFrame(nv21, width, height, rotation, timestampNs);
        }
        frameCount.incrementAndGet();
        lastFrameWidth  = width;
        lastFrameHeight = height;
    }

    // ── GPS via DataChannel ─────────────────────────────────────────────────────

    public void sendNotification(String appName, String title, String text) {
        if (signalingClient == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("type",  "notification");
            json.put("app",   appName);
            json.put("title", title);
            json.put("text",  text);
            json.put("ts",    System.currentTimeMillis());
            signalingClient.send(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendNotification", e);
        }
    }

    public void sendGps(double lat, double lon, double alt, float acc) {
        if (dataChannel == null) return;
        try {
            if (dataChannel.state() != DataChannel.State.OPEN) return;
            JSONObject json = new JSONObject();
            json.put("lat", lat);
            json.put("lon", lon);
            json.put("alt", alt);
            json.put("acc", acc);
            json.put("ts",  System.currentTimeMillis());
            byte[] bytes = json.toString().getBytes();
            dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
        } catch (IllegalStateException e) {
            // dataChannel disposé entre le null-check et l'appel
        } catch (JSONException e) {
            Log.e(TAG, "sendGps error", e);
        }
    }

    // ── Signalisation ───────────────────────────────────────────────────────────

    private void handleSignaling(String raw) {
        final JSONObject msg;
        try { msg = new JSONObject(raw); }
        catch (JSONException e) { Log.e(TAG, "handleSignaling parse: " + raw, e); return; }

        try {
            switch (msg.getString("type")) {

                case "registered":
                    // Enregistré — on attend qu'un observateur demande le flux
                    Log.i(TAG, "Enregistré — caméra en veille");
                    break;

                case "request-offer":
                    executor.execute(() -> {
                        if (offerInProgress) {
                            Log.w(TAG, "Offre déjà en cours, request-offer ignoré");
                            return;
                        }
                        offerInProgress = true;
                        teardownPeerConnection();
                        if (captureListener != null) captureListener.onStartCapture();
                        createOfferAndPeerConnection();
                    });
                    break;

                case "answer":
                    executor.execute(() -> {
                        if (peerConnection == null) return;
                        try {
                            peerConnection.setRemoteDescription(new NoopSdpObserver(),
                                    new SessionDescription(SessionDescription.Type.ANSWER,
                                            msg.getString("sdp")));
                        } catch (JSONException e) {
                            Log.e(TAG, "answer sdp", e);
                        }
                    });
                    break;

                case "ice-candidate":
                    executor.execute(() -> {
                        if (peerConnection == null) return;
                        try {
                            JSONObject c = msg.getJSONObject("candidate");
                            peerConnection.addIceCandidate(new IceCandidate(
                                    c.getString("sdpMid"),
                                    c.getInt("sdpMLineIndex"),
                                    c.getString("candidate")));
                        } catch (JSONException e) {
                            Log.e(TAG, "ice-candidate", e);
                        }
                    });
                    break;

                case "stop-stream":
                    // L'observateur s'est déconnecté ou a changé d'appareil
                    executor.execute(() -> {
                        teardownPeerConnection();
                        if (captureListener != null) captureListener.onStopCapture();
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

    // ── PeerConnection ──────────────────────────────────────────────────────────

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
                    c.put("sdpMid",        candidate.sdpMid);
                    c.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    c.put("candidate",     candidate.sdp);
                    json.put("candidate", c);
                    signalingClient.send(json.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onIceCandidate", e);
                }
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                Log.i(TAG, "PeerConnection: " + state);
                if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        state == PeerConnection.PeerConnectionState.FAILED) {
                    if (!executor.isShutdown()) {
                        executor.execute(() -> {
                            teardownPeerConnection();
                            if (captureListener != null) captureListener.onStopCapture();
                        });
                    }
                }
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
            offerInProgress = false;
            return;
        }

        peerConnection.addTrack(captationVideo.getTrack(), Collections.singletonList("s0"));
        peerConnection.addTrack(captationSon.getTrack(),   Collections.singletonList("s0"));

        DataChannel.Init dcInit = new DataChannel.Init();
        dcInit.ordered = true;
        dataChannel = peerConnection.createDataChannel("telemetry", dcInit);
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long l) {}
            @Override public void onMessage(DataChannel.Buffer b) {}
            @Override public void onStateChange() {
                if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
                    startStatsLoop();
                }
            }
        });

        peerConnection.createOffer(new NoopSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new NoopSdpObserver(), sdp);
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "offer");
                    json.put("sdp",  sdp.description);
                    signalingClient.send(json.toString());
                    Log.i(TAG, "Offre envoyée");
                } catch (JSONException e) {
                    Log.e(TAG, "createOffer send", e);
                } finally {
                    offerInProgress = false;
                }
            }

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "SDP create: " + error);
                offerInProgress = false;
            }
        }, new MediaConstraints());
    }

    private void sendPendingCrashReport() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(CyclopeService.PREFS_CRASHES, Context.MODE_PRIVATE);
        String msg = prefs.getString("crash_msg", null);
        if (msg == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("type",  "crash-report");
            json.put("msg",   msg);
            json.put("trace", prefs.getString("crash_trace", ""));
            json.put("ts",    prefs.getLong("crash_ts", 0));
            signalingClient.send(json.toString());
            Log.i(TAG, "Crash report envoyé au serveur");
        } catch (JSONException e) {
            Log.e(TAG, "sendCrashReport", e);
        }
        prefs.edit().remove("crash_msg").remove("crash_trace").remove("crash_ts").apply();
    }

    private void startStatsLoop() {
        stopStatsLoop();
        statsFuture = statsScheduler.scheduleAtFixedRate(this::sendStats, 1, 1, TimeUnit.SECONDS);
    }

    private void stopStatsLoop() {
        if (statsFuture != null) { statsFuture.cancel(false); statsFuture = null; }
    }

    private void sendStats() {
        if (dataChannel == null) return;
        try {
            if (dataChannel.state() != DataChannel.State.OPEN) return;
            JSONObject json = new JSONObject();
            json.put("type",   "stats");
            json.put("fps",    frameCount.getAndSet(0));
            json.put("width",  lastFrameWidth);
            json.put("height", lastFrameHeight);
            byte[] bytes = json.toString().getBytes();
            dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
        } catch (Exception e) {
            Log.e(TAG, "sendStats", e);
        }
    }

    // Ferme proprement la PeerConnection et le DataChannel — doit tourner sur l'executor
    private void teardownPeerConnection() {
        stopStatsLoop();
        if (dataChannel != null) {
            dataChannel.close();
            dataChannel.dispose();
            dataChannel = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }
        offerInProgress = false;
    }

    // ── Libération des ressources ───────────────────────────────────────────────

    public void release() {
        executor.execute(() -> {
            teardownPeerConnection();
            if (captationVideo != null) { captationVideo.dispose(); captationVideo = null; }
            if (captationSon   != null) { captationSon.dispose();   captationSon   = null; }
            if (factory        != null) { factory.dispose();        factory        = null; }
            if (eglBase        != null) { eglBase.release();        eglBase        = null; }
            if (signalingClient != null) signalingClient.close();
        });
        executor.shutdown();
        statsScheduler.shutdown();
    }

    // ── SdpObserver vide ────────────────────────────────────────────────────────

    private static class NoopSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { Log.e(TAG, "SDP create: " + error); }
        @Override public void onSetFailure(String error)    { Log.e(TAG, "SDP set: "    + error); }
    }
}