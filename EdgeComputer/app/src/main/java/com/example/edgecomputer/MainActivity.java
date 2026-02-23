package com.example.edgecomputer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsetsController;
import android.view.WindowInsets;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.edgecomputer.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    // Chargement de la librairie native lors du démarrage de l'application
    static {
        System.loadLibrary("edgecomputer");
    }

    private static final String TAG = "CameraNDK";
    private static final int PERMISSION_REQUEST_CODE_CAMERA = 1;

    // Utilisation du View Binding pour éviter les findViewById
    private ActivityMainBinding binding;

    private boolean cameraRunning = false;
    private SurfaceHolder surfaceHolder;

    // Méthodes natives
    public native void scan();
    public native void flipCamera();
    public native void setSurface(Surface surface);
    public native void release();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialisation du binding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Mode plein écran immersif
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        // Définition des permissions requises
        String[] requiredPermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
        };

        // Vérification des permissions
        if (!hasPermissions(requiredPermissions)) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE_CAMERA);
        } else {
            initNativeComponents();
        }
    }

    /**
     * Méthode d'initialisation qui est appelée dès que toutes les permissions sont accordées.
     * Elle configure le code natif, le SurfaceView, les listeners des boutons et affiche quelques infos sur la caméra.
     */
    private void initNativeComponents() {
        // Configuration du SurfaceView et de son callback
        SurfaceView surfaceView = binding.surfaceView;
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceCreated");
                // La caméra ne démarre pas automatiquement, on attend le bouton Start
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "surfaceChanged: format=" + format + ", width=" + width + ", height=" + height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceDestroyed");
                if (cameraRunning) {
                    release();
                    cameraRunning = false;
                    binding.startStopButton.setText("Start");
                }
            }
        });

        // Bouton Start/Stop
        binding.startStopButton.setOnClickListener(v -> {
            if (cameraRunning) {
                release();
                cameraRunning = false;
                binding.startStopButton.setText("Start");
            } else {
                Surface surface = surfaceHolder.getSurface();
                if (surface != null && surface.isValid()) {
                    setSurface(surface);
                    cameraRunning = true;
                    binding.startStopButton.setText("Stop");
                }
            }
        });

        // Boutons Scan et Flip : protégés par cameraRunning
        binding.scanButton.setOnClickListener(v -> {
            if (!cameraRunning) {
                Toast.makeText(this, "Démarrez la caméra d'abord", Toast.LENGTH_SHORT).show();
                return;
            }
            scan();
        });

        binding.flipButton.setOnClickListener(v -> {
            if (!cameraRunning) {
                Toast.makeText(this, "Démarrez la caméra d'abord", Toast.LENGTH_SHORT).show();
                return;
            }
            flipCamera();
        });

        // Affichage des informations sur les caméras disponibles
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Log.d(TAG, "Camera ID: " + cameraId);
                Log.d(TAG, "INFO_SUPPORTED_HARDWARE_LEVEL: " +
                        characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
                Log.d(TAG, "INFO_REQUIRED_HARDWARE_LEVEL FULL: " +
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                Log.d(TAG, "INFO_REQUIRED_HARDWARE_LEVEL 3: " +
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3);
                Log.d(TAG, "INFO_REQUIRED_HARDWARE_LEVEL LIMITED: " +
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
                Log.d(TAG, "INFO_REQUIRED_HARDWARE_LEVEL LEGACY: " +
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erreur d'accès à la caméra", e);
        }
    }

    /**
     * Vérifie que toutes les permissions spécifiées sont accordées.
     */
    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gestion de la réponse à la demande de permissions.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE_CAMERA) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initNativeComponents();
            } else {
                Toast.makeText(this,
                        "Les permissions sont nécessaires pour le bon fonctionnement de l'application.",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}
