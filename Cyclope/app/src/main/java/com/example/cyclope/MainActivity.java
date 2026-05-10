package com.example.cyclope;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 1;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
    };

    private Button  btnToggle;
    private TextView tvStatus;
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.scanButton);
        tvStatus  = findViewById(R.id.flipButton) != null
                    ? new TextView(this)   // fallback si layout non modifié
                    : new TextView(this);

        // On réutilise le bouton existant comme toggle
        btnToggle.setText("Démarrer l'agent");
        btnToggle.setOnClickListener(v -> toggleService());

        // Masquer le second bouton s'il existe
        if (findViewById(R.id.flipButton) instanceof Button) {
            ((Button) findViewById(R.id.flipButton)).setVisibility(android.view.View.GONE);
        }
    }

    private void toggleService() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERM_CODE);
            return;
        }
        if (serviceRunning) {
            CyclopeService.stop(this);
            serviceRunning = false;
            btnToggle.setText("Démarrer l'agent");
        } else {
            CyclopeService.start(this);
            serviceRunning = true;
            btnToggle.setText("Arrêter l'agent");
        }
    }

    private boolean hasPermissions() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERM_CODE) return;

        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions requises pour l'agent.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        toggleService();
    }
}