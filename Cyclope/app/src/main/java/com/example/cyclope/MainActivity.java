package com.example.cyclope;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 1;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
    };

    private Button  btnToggle;
    private TextView tvStatus;
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.scan_button);
        btnToggle.setText("Démarrer l'agent");
        btnToggle.setOnClickListener(v -> toggleService());

        Button btnFlip = findViewById(R.id.flip_button);
        if (btnFlip != null) btnFlip.setVisibility(android.view.View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationListenerPermission();
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

    // Vérifie si l'accès aux notifications est accordé et propose de l'activer sinon
    private void checkNotificationListenerPermission() {
        if (isNotificationListenerEnabled()) return;

        new AlertDialog.Builder(this)
                .setTitle("Accès aux notifications")
                .setMessage("Pour capturer les notifications du téléphone, autorise l'accès dans les paramètres système.")
                .setPositiveButton("Ouvrir les paramètres", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                })
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private boolean isNotificationListenerEnabled() {
        ComponentName cn   = new ComponentName(this, CaptationNotification.class);
        String        flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
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