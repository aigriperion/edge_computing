package com.example.cyclope;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.cyclope.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("cyclope");
    }

    private static final String TAG = "CameraNDK";
    private static final int PERMISSION_REQUEST_CODE_CAMERA = 1;

    private ActivityMainBinding binding;
    private boolean nativeInitialized = false;

    // Méthodes natives
    public native void scan();
    public native void flipCamera();
    public native void setSurface(Surface surface);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String[] requiredPermissions = {
                Manifest.permission.CAMERA
        };

        if (!hasPermissions(requiredPermissions)) {
            ActivityCompat.requestPermissions(
                    this,
                    requiredPermissions,
                    PERMISSION_REQUEST_CODE_CAMERA
            );
        } else {
            initNativeComponents();
        }
    }

    /**
     * Initialise la surface, les boutons et les logs caméra.
     * Corrige le bug du premier lancement où la surface peut déjà être valide
     * avant l'ajout du callback.
     */
    private void initNativeComponents() {
        if (nativeInitialized) {
            Log.d(TAG, "initNativeComponents déjà exécuté, on ignore.");
            return;
        }

        nativeInitialized = true;

        SurfaceView surfaceView = binding.surfaceView;
        SurfaceHolder surfaceHolder = surfaceView.getHolder();

        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceCreated");

                Surface surface = holder.getSurface();
                if (surface != null && surface.isValid()) {
                    Log.d(TAG, "surfaceCreated -> surface valide, envoi au natif");
                    setSurface(surface);
                } else {
                    Log.w(TAG, "surfaceCreated -> surface nulle ou invalide");
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "surfaceChanged: format=" + format + ", width=" + width + ", height=" + height);

                Surface surface = holder.getSurface();
                if (surface != null && surface.isValid()) {
                    Log.d(TAG, "surfaceChanged -> surface valide, envoi au natif");
                    setSurface(surface);
                } else {
                    Log.w(TAG, "surfaceChanged -> surface nulle ou invalide");
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceDestroyed");
            }
        });

        // Correctif principal :
        // si la surface a déjà été créée avant qu'on ajoute le callback,
        // on l'envoie immédiatement au natif.
        Surface existingSurface = surfaceHolder.getSurface();
        if (existingSurface != null && existingSurface.isValid()) {
            Log.d(TAG, "Surface déjà valide après addCallback -> initialisation native immédiate");
            setSurface(existingSurface);
        } else {
            Log.d(TAG, "Surface pas encore prête après addCallback");
        }

        binding.scanButton.setOnClickListener(v -> {
            Log.d(TAG, "scanButton clicked");
            scan();
        });

        binding.flipButton.setOnClickListener(v -> {
            Log.d(TAG, "flipButton clicked");
            flipCamera();
        });

        logAvailableCameras();
    }

    /**
     * Vérifie que toutes les permissions demandées sont accordées.
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
     * Retour de la demande de permission runtime.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE_CAMERA) {
            boolean allGranted = true;

            if (grantResults.length == 0) {
                allGranted = false;
            } else {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            }

            if (allGranted) {
                Log.d(TAG, "Permission caméra accordée");
                initNativeComponents();
            } else {
                Toast.makeText(
                        this,
                        "La permission caméra est nécessaire pour faire fonctionner l'application.",
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }
        }
    }

    /**
     * Affiche dans le log les infos des caméras disponibles.
     */
    private void logAvailableCameras() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (manager == null) {
            Log.e(TAG, "CameraManager est null");
            return;
        }

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                Integer hardwareLevel =
                        characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                Integer lensFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING);
                Integer sensorOrientation =
                        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                Log.d(TAG, "-----------------------------------");
                Log.d(TAG, "Camera ID: " + cameraId);
                Log.d(TAG, "Hardware level: " + hardwareLevel);
                Log.d(TAG, "Lens facing: " + lensFacing);
                Log.d(TAG, "Sensor orientation: " + sensorOrientation);
                Log.d(TAG, "FULL constant: " + CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                Log.d(TAG, "LEVEL_3 constant: " + CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3);
                Log.d(TAG, "LIMITED constant: " + CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
                Log.d(TAG, "LEGACY constant: " + CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erreur d'accès à la caméra", e);
        }
    }
}