package com.custom.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

/** Android 11 does not resolve .local through InetAddress; discover Valetudo through DNS-SD. */
final class LocalVacuum implements NsdManager.DiscoveryListener {
    interface Callback { void found(String address); void failed(); }
    private final NsdManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private boolean active = true;
    private boolean discovering;
    private boolean resolving;
    private final Runnable timeout = this::onTimeout;
    private void onTimeout() { if (active) { close(); callback.failed(); } }

    LocalVacuum(Context context, Callback callback) {
        this.callback = callback;
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        handler.postDelayed(timeout, 10000);
        try { manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, this); }
        catch (RuntimeException e) { handler.post(timeout); }
    }

    @Override public void onDiscoveryStarted(String type) {
        discovering = true;
        if (!active) stopDiscovery();
    }
    @Override public void onServiceFound(NsdServiceInfo service) {
        if (!active || resolving || !"Valetudo MedicalUnwelcomeShark Web".equalsIgnoreCase(service.getServiceName())) return;
        resolving = true;
        manager.resolveService(service, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int error) { resolving = false; }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                handler.post(() -> {
                    if (!active || info.getHost() == null) return;
                    String host = info.getHost().getHostAddress();
                    if (host.contains(":")) host = "[" + host + "]";
                    String address = "http://" + host + (info.getPort() == 80 ? "" : ":" + info.getPort());
                    close();
                    callback.found(address);
                });
            }
        });
    }
    @Override public void onServiceLost(NsdServiceInfo service) { }
    @Override public void onDiscoveryStopped(String type) { discovering = false; }
    @Override public void onStartDiscoveryFailed(String type, int error) { handler.post(timeout); }
    @Override public void onStopDiscoveryFailed(String type, int error) { discovering = false; }
    private void stopDiscovery() {
        if (discovering) {
            discovering = false;
            try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) { }
        }
    }
    void close() {
        active = false;
        handler.removeCallbacks(timeout);
        stopDiscovery();
    }
}
