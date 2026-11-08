package com.custom.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/** Fixed worker-control requests. Never substitutes individual app-settings actions. */
final class CleanerControl {
    interface Listener { void status(boolean busy, String message); }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String request;
    private boolean busy;
    private boolean registered;
    private boolean closed;
    private boolean restarting;

    CleanerControl(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.context.registerReceiver(receiver, new IntentFilter("com.custom.tvhome.CLEANER_RESULT"),
                "android.permission.DUMP", handler);
        registered = true;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!busy || closed || !request.equals(intent.getStringExtra("request_id"))) return;
            String result = intent.getStringExtra("result");
            if ((!restarting && "ready".equals(result)) || (restarting && "restarted".equals(result))) {
                complete("restarted".equals(result)
                        ? "Worker restarted. Apps will close automatically when you return home."
                        : "Worker running. Automatic cleanup is active.");
            } else if ("error".equals(result)) {
                complete("The worker could not restart. Try again.");
            }
        }
    };

    private final Runnable timeout = () -> {
        if (closed) return;
        complete("Controller offline. Reconnect from a computer after a TV restart or power loss.");
    };

    void check() { send("PING", false); }
    void restart() { send("RESTART", true); }

    private void send(String command, boolean restart) {
        if (closed || (busy && (restarting || !restart))) return;
        // A deliberate restart supersedes a slow status check rather than silently ignoring OK.
        handler.removeCallbacks(timeout);
        busy = true;
        restarting = restart;
        request = UUID.randomUUID().toString().replace("-", "");
        listener.status(true, restart ? "Restarting worker…" : "Checking worker…");
        handler.postDelayed(timeout, restart ? 15000 : 6000);
        Log.i("TVHomeCleaner", command + " " + request);
    }

    private void complete(String message) {
        busy = false;
        handler.removeCallbacks(timeout);
        listener.status(false, message);
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
    }
}
