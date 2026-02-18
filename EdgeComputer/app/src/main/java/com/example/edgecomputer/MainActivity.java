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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qalycles.rinnegan.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    // Chargement de la librairie native lors du démarrage de l'application
    static {
        System.loadLibrary("rinnegan");
    }

    private static final String TAG = "CameraNDK";
    private static final int PERMISSION_REQUEST_CODE_CAMERA = 1;

    // Utilisation du View Binding pour éviter les findViewById
    private ActivityMainBinding binding;

    // Méthodes natives
    //public native void onCreateJNI(AppCompatActivity callerActivity, AssetManager assetManager);
    public native void scan();
    public native void flipCamera();
    public native void setSurface(Surface surface);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialisation du binding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Définition des permissions requises
        String[] requiredPermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                //Manifest.permission.WRITE_EXTERNAL_STORAGE,
                //Manifest.permission.READ_EXTERNAL_STORAGE
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
        // Initialisation native avec l'Activity et les assets
        //onCreateJNI(this, getAssets());

        // Configuration du SurfaceView et de son callback
        SurfaceView surfaceView = binding.surfaceView;
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceCreated");
                setSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.v(TAG, "surfaceChanged: format=" + format + ", width=" + width + ", height=" + height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.v(TAG, "surfaceDestroyed");
            }
        });

        // Configuration des listeners des boutons à l'aide de lambda expressions
        binding.scanButton.setOnClickListener(v -> scan());
        binding.flipButton.setOnClickListener(v -> flipCamera());

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
     *
     * @param permissions Tableau de permissions à vérifier.
     * @return true si toutes les permissions sont accordées, false sinon.
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
     *
     * Si toutes les permissions sont accordées, l'initialisation native est lancée.
     * Sinon, un message est affiché et l'application se ferme.
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