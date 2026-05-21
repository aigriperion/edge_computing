package com.example.cyclope;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ServerDiscovery {

    private static final String TAG      = "ServerDiscovery";
    static final         int    UDP_PORT = 41234;

    public interface Callback {
        void onServerFound(String wsUrl);
    }

    private volatile boolean          running = false;
    private DatagramSocket            socket;
    private WifiManager.MulticastLock multicastLock;
    private ExecutorService           scanPool;

    public void start(Context ctx, Callback callback) {
        running = true;

        WifiManager wifiManager = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        // ── Méthode 1 : UDP broadcast ────────────────────────────────────────
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("cyclope_discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        new Thread(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new java.net.InetSocketAddress(UDP_PORT));
                Log.i(TAG, "UDP : écoute sur le port " + UDP_PORT);
                byte[] buf = new byte[256];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String data = new String(packet.getData(), 0, packet.getLength());
                    JSONObject json = new JSONObject(data);
                    if ("cyclope-server".equals(json.optString("type"))) {
                        String ip   = packet.getAddress().getHostAddress();
                        int    port = json.optInt("port", 3000);
                        String url  = "ws://" + ip + ":" + port;
                        Log.i(TAG, "UDP : serveur trouvé → " + url);
                        notifyFound(url, callback);
                    }
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "UDP : erreur", e);
            }
        }, "ServerDiscovery-UDP").start();

        // ── Méthode 2 : scan HTTP du sous-réseau (fallback si UDP bloqué) ────
        if (wifiManager != null) {
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            int ip = dhcp.ipAddress;
            String subnetBase = String.format("%d.%d.%d.",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff));
            Log.i(TAG, "HTTP scan : sous-réseau " + subnetBase + "0/24");
            startHttpScan(subnetBase, callback);
        } else {
            Log.w(TAG, "WifiManager indisponible, scan HTTP désactivé");
        }
    }

    private void startHttpScan(String subnetBase, Callback callback) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(400, TimeUnit.MILLISECONDS)
                .readTimeout(400, TimeUnit.MILLISECONDS)
                .build();

        AtomicBoolean found = new AtomicBoolean(false);
        scanPool = Executors.newFixedThreadPool(32);

        for (int i = 1; i <= 254; i++) {
            final String ip = subnetBase + i;
            scanPool.execute(() -> {
                if (!running || found.get()) return;
                try {
                    Request req = new Request.Builder()
                            .url("http://" + ip + ":3000/cyclope")
                            .build();
                    Response resp = httpClient.newCall(req).execute();
                    String body = resp.body() != null ? resp.body().string() : "";
                    resp.close();
                    if (!resp.isSuccessful()) return;
                    JSONObject json = new JSONObject(body);
                    if ("cyclope-server".equals(json.optString("type"))) {
                        int port = json.optInt("port", 3000);
                        String url = "ws://" + ip + ":" + port;
                        Log.i(TAG, "HTTP : serveur trouvé → " + url);
                        if (found.compareAndSet(false, true)) {
                            notifyFound(url, callback);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void notifyFound(String url, Callback callback) {
        stop();
        callback.onServerFound(url);
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
        }
        if (scanPool != null) {
            scanPool.shutdownNow();
            scanPool = null;
        }
    }
}