package com.example.cyclope;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CaptationGPS {

    private static final String TAG              = "CaptationGPS";
    private static final long   REFRESH_SEC      = 30;  // re-envoi position même sans mouvement
    private static final long   WATCHDOG_SEC     = 60;  // vérification silence GPS
    private static final long   SILENCE_THRESHOLD = 90; // secondes sans update → re-register

    public interface Listener {
        void onGpsUpdate(double lat, double lon, double alt, float acc);
    }

    private final LocationManager           locationManager;
    private final Listener                  listener;
    private       LocationListener          locationListener;

    private volatile Location lastLocation;
    private volatile long     lastUpdateMs = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> refreshFuture;
    private ScheduledFuture<?> watchdogFuture;

    public CaptationGPS(Context ctx, Listener listener) {
        this.locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        this.listener = listener;
    }

    public void start() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                lastLocation  = location;
                lastUpdateMs  = System.currentTimeMillis();
                listener.onGpsUpdate(
                        location.getLatitude(),
                        location.getLongitude(),
                        location.getAltitude(),
                        location.getAccuracy());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Log.w(TAG, "Provider désactivé : " + provider);
                // Si GPS désactivé, on s'assure que NETWORK_PROVIDER est bien inscrit
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    try {
                        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            locationManager.requestLocationUpdates(
                                    LocationManager.NETWORK_PROVIDER, 1000, 0f, this);
                            Log.i(TAG, "Basculement sur NETWORK_PROVIDER");
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Permission GPS manquante", e);
                    }
                }
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                Log.i(TAG, "Provider réactivé : " + provider);
                // Re-demander les updates sur ce provider
                try {
                    locationManager.requestLocationUpdates(provider, 1000, 0f, this);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission GPS manquante", e);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        // Position immédiate via dernière position connue (évite d'attendre le premier fix)
        tryLastKnownLocation();
        requestUpdates();
        startRefreshTimer();
        startWatchdog();
    }

    public void stop() {
        if (refreshFuture  != null) { refreshFuture.cancel(false);  refreshFuture  = null; }
        if (watchdogFuture != null) { watchdogFuture.cancel(false); watchdogFuture = null; }
        scheduler.shutdown();
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void requestUpdates() {
        if (locationListener == null) return;
        try {
            boolean gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean net = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (gps) locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,     1000, 0f, locationListener);
            if (net) locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000, 0f, locationListener);
            if (!gps && !net) Log.w(TAG, "Aucun provider GPS disponible");
        } catch (SecurityException e) {
            Log.e(TAG, "Permission GPS manquante", e);
        }
    }

    private void tryLastKnownLocation() {
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locationManager.isProviderEnabled(provider)) continue;
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null) {
                    lastLocation = loc;
                    listener.onGpsUpdate(loc.getLatitude(), loc.getLongitude(),
                            loc.getAltitude(), loc.getAccuracy());
                    Log.d(TAG, "Position initiale depuis " + provider);
                    return;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission GPS manquante", e);
        }
    }

    // Re-envoie la dernière position connue toutes les REFRESH_SEC secondes
    private void startRefreshTimer() {
        refreshFuture = scheduler.scheduleAtFixedRate(() -> {
            Location loc = lastLocation;
            if (loc != null) {
                listener.onGpsUpdate(loc.getLatitude(), loc.getLongitude(),
                        loc.getAltitude(), loc.getAccuracy());
            }
        }, REFRESH_SEC, REFRESH_SEC, TimeUnit.SECONDS);
    }

    // Surveille le silence GPS : si aucun update depuis SILENCE_THRESHOLD sec, re-register
    private void startWatchdog() {
        watchdogFuture = scheduler.scheduleAtFixedRate(() -> {
            if (lastUpdateMs == 0) return; // pas encore de fix, normal
            long silenceSec = (System.currentTimeMillis() - lastUpdateMs) / 1000;
            if (silenceSec > SILENCE_THRESHOLD) {
                Log.w(TAG, "GPS silencieux depuis " + silenceSec + "s — re-enregistrement");
                if (locationListener != null) {
                    try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
                }
                requestUpdates();
            }
        }, WATCHDOG_SEC, WATCHDOG_SEC, TimeUnit.SECONDS);
    }
}