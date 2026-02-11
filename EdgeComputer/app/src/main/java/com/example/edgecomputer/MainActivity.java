package com.example.edgecomputer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpCamera1;

public class MainActivity extends AppCompatActivity implements
        SensorEventListener,
        ConnectChecker,
        SurfaceHolder.Callback {

    private static final String TAG = "EdgeRTMP";
    private static final int REQ_PERMS = 1001;

    private static final String RTMP_URL = "rtmp://192.168.0.28:1935/live/stream";

    // face up/down
    private static final float FACE_THRESHOLD = 7.0f;
    private boolean isFaceDown = false;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private RtmpCamera1 rtmpCamera1;

    private boolean permissionGranted = false;
    private boolean surfaceReady = false;
    private boolean started = false;

    // Sensors
    private SensorManager sensorManager;
    private Sensor accelerometer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surface);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        String[] perms = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET
        };

        boolean allGranted = true;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, perms, REQ_PERMS);
        } else {
            permissionGranted = true;
            tryStartStreaming();
        }
    }

    private void tryStartStreaming() {
        if (started) return;

        if (!permissionGranted) {
            Log.i(TAG, "Waiting for permissions...");
            return;
        }
        if (!surfaceReady) {
            Log.i(TAG, "Waiting for surface...");
            return;
        }

        // Créer la caméra ICI (et pas dans onCreate)
        if (rtmpCamera1 == null) {
            try {
                rtmpCamera1 = new RtmpCamera1(surfaceView, this);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to init RtmpCamera1 (camera service)", e);
                // Souvent: caméra déjà utilisée ou permission pas vraiment accordée
                return;
            }
        }

        int width = 640;
        int height = 480;
        int fps = 20;
        int bitrate = 1500 * 1024;
        int rotation = 0;

        boolean preparedVideo = rtmpCamera1.prepareVideo(width, height, fps, bitrate, rotation);
        if (!preparedVideo) {
            Log.e(TAG, "prepareVideo failed");
            return;
        }

        // Audio optionnel : commente si tu veux video-only
        boolean preparedAudio = rtmpCamera1.prepareAudio();

        Log.i(TAG, "Starting RTMP stream: " + RTMP_URL);
        rtmpCamera1.startStream(RTMP_URL);
        started = true;
    }

    private void stopStreaming() {
        started = false;
        if (rtmpCamera1 != null) {
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            if (rtmpCamera1.isOnPreview()) rtmpCamera1.stopPreview();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        stopStreaming();
    }

    // Surface callbacks
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceReady = true;
        Log.i(TAG, "surfaceCreated");
        tryStartStreaming();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        Log.i(TAG, "surfaceDestroyed");
        stopStreaming();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        // nothing
    }

    // Permissions runtime
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int g : grantResults) ok &= (g == PackageManager.PERMISSION_GRANTED);
            permissionGranted = ok;

            Log.i(TAG, "Permissions granted=" + permissionGranted);
            if (permissionGranted) tryStartStreaming();
        }
    }

    // Accelerometer -> switch cam
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

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
        if (rtmpCamera1 == null) return;
        try {
            rtmpCamera1.switchCamera();
            Log.i(TAG, "Camera switched");
        } catch (Exception e) {
            Log.e(TAG, "switchCamera failed", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ConnectChecker callbacks
    @Override
    public void onConnectionStarted(String url) {
        Log.i(TAG, "Connection started: " + url);
    }

    @Override
    public void onConnectionSuccess() {
        Log.i(TAG, "Connection success");
    }

    @Override
    public void onConnectionFailed(String reason) {
        Log.e(TAG, "Connection failed: " + reason);
        stopStreaming();
    }

    @Override
    public void onNewBitrate(long bitrate) { }

    @Override
    public void onDisconnect() {
        Log.i(TAG, "Disconnected");
    }

    @Override
    public void onAuthError() {
        Log.e(TAG, "Auth error");
    }

    @Override
    public void onAuthSuccess() {
        Log.i(TAG, "Auth success");
    }
}
