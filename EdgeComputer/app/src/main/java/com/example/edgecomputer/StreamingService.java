package com.example.edgecomputer;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpCamera1;

public class StreamingService extends Service implements ConnectChecker, SensorEventListener {

    public static final String ACTION_START = "com.example.edgecomputer.action.START";
    public static final String ACTION_STOP = "com.example.edgecomputer.action.STOP";
    private static final String TAG = "EdgeRTMPService";
    private static final String CHANNEL_ID = "edge_stream_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final float FACE_THRESHOLD = 7.0f;

    private static final String RTMP_URL = "rtmp://192.168.0.28:1935/live/stream";

    private RtmpCamera1 rtmpCamera1;
    private boolean started = false;
    private boolean isFaceDown = false;
    private SensorManager sensorManager;
    private Sensor accelerometer;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Initialisation du streaming"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop action received from notification");
            stopStreamingAndService();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startStreamingIfPossible();
            if (sensorManager != null && accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        }
        return START_STICKY;
    }

    private void startStreamingIfPossible() {
        if (started) {
            return;
        }

        if (!hasRuntimePermissions()) {
            Log.e(TAG, "Permissions manquantes (camera/audio)");
            stopSelf();
            return;
        }

        try {
            if (rtmpCamera1 == null) {
                // Constructor without preview surface for background streaming.
                rtmpCamera1 = new RtmpCamera1(this, this);
            }

            int width = 640;
            int height = 480;
            int fps = 20;
            int bitrate = 1500 * 1024;
            int rotation = 0;

            boolean preparedVideo = rtmpCamera1.prepareVideo(width, height, fps, bitrate, rotation);
            boolean preparedAudio = rtmpCamera1.prepareAudio();

            if (!preparedVideo || !preparedAudio) {
                Log.e(TAG, "prepareVideo/prepareAudio failed");
                stopSelf();
                return;
            }

            rtmpCamera1.startStream(RTMP_URL);
            started = true;
            updateNotification("Streaming actif en arriere-plan");
            Log.i(TAG, "Streaming started: " + RTMP_URL);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start streaming", e);
            stopSelf();
        }
    }

    private boolean hasRuntimePermissions() {
        boolean camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        return camGranted && micGranted;
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, StreamingService.class);
        stopIntent.setAction(ACTION_STOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("EdgeComputer")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Stop streaming", stopPendingIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Edge Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void stopStreamingAndService() {
        stopStreamingInternal();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void stopStreamingInternal() {
        if (rtmpCamera1 != null) {
            if (rtmpCamera1.isStreaming()) {
                rtmpCamera1.stopStream();
            }
            if (rtmpCamera1.isOnPreview()) {
                rtmpCamera1.stopPreview();
            }
        }
        started = false;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopStreamingInternal();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConnectionStarted(String url) {
        Log.i(TAG, "Connection started: " + url);
    }

    @Override
    public void onConnectionSuccess() {
        Log.i(TAG, "Connection success");
        updateNotification("Streaming connecte");
    }

    @Override
    public void onConnectionFailed(String reason) {
        Log.e(TAG, "Connection failed: " + reason);
        updateNotification("Erreur de connexion RTMP");
        stopSelf();
    }

    @Override
    public void onNewBitrate(long bitrate) {
        // No-op
    }

    @Override
    public void onDisconnect() {
        Log.i(TAG, "Disconnected");
        updateNotification("Streaming deconnecte");
    }

    @Override
    public void onAuthError() {
        Log.e(TAG, "Auth error");
        updateNotification("Erreur d'authentification RTMP");
        stopSelf();
    }

    @Override
    public void onAuthSuccess() {
        Log.i(TAG, "Auth success");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        float z = event.values[2];
        boolean faceDownNow = (z < -FACE_THRESHOLD);
        boolean faceUpNow = (z > FACE_THRESHOLD);

        if (faceDownNow && !isFaceDown) {
            isFaceDown = true;
            switchCamera();
        } else if (faceUpNow && isFaceDown) {
            isFaceDown = false;
            switchCamera();
        }
    }

    private void switchCamera() {
        if (rtmpCamera1 == null) {
            return;
        }
        try {
            rtmpCamera1.switchCamera();
            Log.i(TAG, "Camera switched");
        } catch (Exception e) {
            Log.e(TAG, "switchCamera failed", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op
    }
}
