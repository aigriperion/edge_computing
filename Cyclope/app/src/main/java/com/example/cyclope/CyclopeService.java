package com.example.cyclope;

import android.app.Notification;
import android.provider.Settings;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class CyclopeService extends Service {

    static {
        System.loadLibrary("cyclope");
    }

    private static final String TAG         = "CyclopeService";
    private static final String CHANNEL_ID  = "cyclope_agent";
    private static final int    NOTIF_ID    = 1;

    private WebRtcClient    webRtcClient;
    private LocationManager locationManager;
    private LocationListener locationListener;

    // ── Méthodes natives ────────────────────────────────────────────────────
    private native void nativeStart();
    private native void nativeStop();
    private native void nativeFlipCamera();
    private native void nativeSetWebRtcCallback(Object cb);
    private native void nativeSetGpsData(double lat, double lon, double alt, float accuracy);

    // ── Cycle de vie du service ─────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Démarrage…"));
        Log.i(TAG, "Agent démarré");

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        webRtcClient = new WebRtcClient(this, deviceId, deviceName);
        webRtcClient.setFlipListener(this::nativeFlipCamera);
        webRtcClient.init();
        nativeSetWebRtcCallback(webRtcClient);
        nativeStart();
        setupGps();

        updateNotification("En ligne — transmission active");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Agent arrêté");

        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        nativeStop();
        nativeSetWebRtcCallback(null);
        if (webRtcClient != null) {
            webRtcClient.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── GPS ─────────────────────────────────────────────────────────────────
    private void setupGps() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                nativeSetGpsData(
                        location.getLatitude(),
                        location.getLongitude(),
                        location.getAltitude(),
                        location.getAccuracy());
                if (webRtcClient != null) {
                    webRtcClient.sendGps(
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getAltitude(),
                            location.getAccuracy());
                }
            }
        };

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000, 0f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 1000, 0f, locationListener);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission GPS manquante", e);
        }
    }

    // ── Notification persistante ────────────────────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cyclope Agent",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Transmission vidéo / GPS en cours");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Cyclope")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ── Helpers statiques pour l'Activity ───────────────────────────────────
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