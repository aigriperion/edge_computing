package com.example.cyclope;

import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import java.util.concurrent.TimeUnit;

public class SignalingClient {

    private static final String TAG = "SignalingClient";

    public interface Listener {
        void onConnected();
        void onMessage(String message);
        void onDisconnected();
    }

    private final OkHttpClient client;
    private final Listener listener;
    private WebSocket ws;

    public SignalingClient(Listener listener) {
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    public void connect(String url) {
        Log.i(TAG, "Connexion à " + url);
        Request request = new Request.Builder().url(url).build();
        ws = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                Log.i(TAG, "WebSocket ouvert");
                listener.onConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                listener.onMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                listener.onDisconnected();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                Log.e(TAG, "Erreur WebSocket : " + t.getMessage());
                listener.onDisconnected();
            }
        });
    }

    public void send(String message) {
        if (ws != null) ws.send(message);
    }

    public void close() {
        if (ws != null) {
            ws.close(1000, "close");
            ws = null;
        }
        client.dispatcher().executorService().shutdown();
    }
}