package com.custom.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Fire OS always routes the Home button to Amazon's launcher, which cannot be disabled, and does
 * not pass the Home key to accessibility services. So whenever Amazon's home screen comes to the
 * front (Home button, boot, an app exiting) this service:
 *  1. instantly covers the screen with an overlay drawing our home screen (no flash of Amazon's), and
 *  2. brings HomeActivity to the front underneath, which removes the overlay once it has drawn.
 *
 * The earliest signal of a Home press is the system log line where Amazon's home is started
 * ("from uid 0"), hundreds of milliseconds before its window appears. Reading it needs READ_LOGS,
 * granted once over adb; without it the service falls back to window events alone.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME_CLASS = "com.amazon.tv.launcher.ui.HomeActivity";
    private static final String OUR_PACKAGE = "com.custom.tvhome";
    // Opening Settings from elsewhere hops through this activity and then Amazon's home.
    private static final String SETTINGS_TRAMPOLINE =
            "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler";
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000;
    private static final long DEBOUNCE_MS = 150;
    // Amazon's settings screen can take ~800 ms to appear after the hop through its home.
    private static final long SETTLE_MS = 1100;
    private static final long TRAMPOLINE_MS = 1500;
    private static final long OVERLAY_MAX_MS = 1500;
    // Prefix: Fire OS 8 uses HomeActivity_vNext, Fire OS 7 HomeActivity.
    private static final String HOME_START = "cmp=com.amazon.tv.launcher/.ui.HomeActivity";
    private static final String FROM_SYSTEM = "} from uid 0";

    private static HomeButtonService instance;
    private static volatile long settingsLaunchedAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = new Runnable() {
        @Override
        public void run() {
            coverAndGoHome();
        }
    };
    private final Runnable removeOverlay = new Runnable() {
        @Override
        public void run() {
            removeOverlayNow();
        }
    };
    private WindowManager windowManager;
    private HomeView overlay;
    private long lastHomeLaunch;
    private String lastScreenCls = "";
    private long lastScreenTime;
    private long passThroughUntil;
    private volatile boolean watchingLog;
    private volatile Process logcat;

    private final Runnable homePressed = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(redirect);
            passThroughUntil = 0;
            coverAndGoHome();
        }
    };
    private final Runnable homePassThrough = new Runnable() {
        @Override
        public void run() {
            // Another app sent the user to Amazon's home on the way somewhere else (e.g. Settings).
            passThroughUntil = SystemClock.uptimeMillis() + TRAMPOLINE_MS;
        }
    };

    static boolean isOverlayShowing() {
        return instance != null && instance.overlay != null;
    }

    /** Our Settings tile is opening Settings; Amazon's home may flash by on the way. */
    static void noteSettingsLaunch() {
        settingsLaunchedAt = SystemClock.uptimeMillis();
    }

    /** Called by HomeActivity once it is on screen underneath the overlay. */
    static void hideOverlay() {
        if (instance != null) instance.removeOverlayNow();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = getSystemService(WindowManager.class);
        startLogWatcher();
        // Right after boot Amazon's home screen is showing; replace it.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) goHome();
    }

    @Override
    public void onDestroy() {
        stopLogWatcher();
        removeOverlayNow();
        handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Fire OS 8.1 never delivers HOME here; this only helps on builds that do.
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) coverAndGoHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String p = pkg.toString();
        String c = cls.toString();
        long now = SystemClock.uptimeMillis();
        if (AMAZON_LAUNCHER.equals(p) && c.startsWith(AMAZON_HOME_CLASS)) {
            handler.removeCallbacks(redirect);
            // On the way to Settings Amazon's home flashes by for ~170 ms; wait to see if it stays.
            boolean passingThrough = now < passThroughUntil
                    || now - settingsLaunchedAt < TRAMPOLINE_MS
                    || (SETTINGS_TRAMPOLINE.equals(lastScreenCls) && now - lastScreenTime < TRAMPOLINE_MS);
            if (passingThrough) handler.postDelayed(redirect, SETTLE_MS);
            else coverAndGoHome();
        } else if (OUR_PACKAGE.equals(p)) {
            // HomeActivity releases the overlay itself once it has drawn beneath it.
            handler.removeCallbacks(redirect);
            noteScreen(c, now);
        } else if (isNewScreen(p, c)) {
            handler.removeCallbacks(redirect);
            removeOverlayNow();
            noteScreen(c, now);
        }
    }

    private void noteScreen(String cls, long now) {
        lastScreenCls = cls;
        lastScreenTime = now;
    }

    /** Toasts, system overlays and Amazon home's own panels don't mean the user left the home screen. */
    private static boolean isNewScreen(String pkg, String cls) {
        if (cls.startsWith("android.widget.Toast") || "com.android.systemui".equals(pkg)) return false;
        if (AMAZON_LAUNCHER.equals(pkg)) return cls.startsWith(AMAZON_LAUNCHER + ".ui.");
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    private void startLogWatcher() {
        if (watchingLog) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) return;
        watchingLog = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (watchingLog) {
                    readLog();
                    if (watchingLog) SystemClock.sleep(1000);
                }
            }
        }, "home-log-watcher");
        t.setDaemon(true);
        t.start();
    }

    private void stopLogWatcher() {
        watchingLog = false;
        Process p = logcat;
        if (p != null) p.destroy();
    }

    /** Follows ActivityTaskManager's log from now on, until logcat exits. */
    private void readLog() {
        Process p = null;
        try {
            // Android 10+ logs app starts as ActivityTaskManager, Android 9 (Fire OS 7) as ActivityManager.
            p = new ProcessBuilder("logcat", "-v", "brief", "-T", "1",
                    "ActivityTaskManager:I", "ActivityManager:I", "*:S").redirectErrorStream(true).start();
            logcat = p;
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()), 2048);
            String line;
            while (watchingLog && (line = in.readLine()) != null) {
                if (!line.contains(HOME_START)) continue;
                handler.postAtFrontOfQueue(line.contains(FROM_SYSTEM) ? homePressed : homePassThrough);
            }
        } catch (IOException ignored) {
        } finally {
            if (p != null) p.destroy();
            logcat = null;
        }
    }

    private void coverAndGoHome() {
        showOverlay();
        goHome();
    }

    private void showOverlay() {
        if (overlay != null || windowManager == null) return;
        // Built fresh each time so it shows the current focus; the clock in it is live.
        HomeView view = new HomeView(this, false, null);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            windowManager.addView(view, lp);
        } catch (RuntimeException e) {
            return;
        }
        overlay = view;
        handler.removeCallbacks(removeOverlay);
        handler.postDelayed(removeOverlay, OVERLAY_MAX_MS);
    }

    private void removeOverlayNow() {
        handler.removeCallbacks(removeOverlay);
        if (overlay == null) return;
        HomeView view = overlay;
        overlay = null;
        try {
            windowManager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
        }
    }

    private void goHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHomeLaunch < DEBOUNCE_MS) {
            // A launch is already on its way; check again once it has landed.
            handler.removeCallbacks(redirect);
            handler.postDelayed(redirect, DEBOUNCE_MS);
            return;
        }
        lastHomeLaunch = now;
        Intent intent = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            removeOverlayNow();
        }
    }
}
