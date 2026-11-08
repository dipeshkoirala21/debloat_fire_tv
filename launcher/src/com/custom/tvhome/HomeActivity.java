package com.custom.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

/** Ad-free TV home screen. All drawing lives in HomeView; this activity handles lifecycle and launching. */
public class HomeActivity extends Activity implements HomeView.Listener {

    private HomeView home;
    private boolean releasePending;

    // While home stays on screen, keep the weather current. refreshIfStale only fetches when the
    // reading is 20 min old (or a failed attempt is 30 s old), so checking every minute is cheap.
    private static final long WEATHER_CHECK_MS = 60 * 1000;
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.refreshIfStale(HomeActivity.this);
            home.postDelayed(this, WEATHER_CHECK_MS);
        }
    };

    /** Fires after one of our frames has actually been rendered; only then may the overlay go. */
    private final Window.OnFrameMetricsAvailableListener frameRendered =
            new Window.OnFrameMetricsAvailableListener() {
                @Override
                public void onFrameMetricsAvailable(Window window, FrameMetrics metrics, int dropped) {
                    if (!releasePending || !hasWindowFocus()) return;
                    releasePending = false;
                    // One more vsync so the compositor has latched the frame.
                    home.postOnAnimation(new Runnable() {
                        @Override
                        public void run() {
                            HomeButtonService.hideOverlay();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        home = new HomeView(this, true, this);
        setContentView(home);
        getWindow().addOnFrameMetricsAvailableListener(frameRendered, new Handler(Looper.getMainLooper()));
        home.focusInstantly(AppCatalog.focused);
        // When the overlay is covering the screen it already shows this frame; don't animate under it.
        if (savedInstanceState == null && !HomeButtonService.isOverlayShowing()) home.playEntrance();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getCurrentFocus() == null) home.focusInstantly(AppCatalog.focused);
        Weather.refreshIfStale(this);
        home.removeCallbacks(weatherTick);
        home.postDelayed(weatherTick, WEATHER_CHECK_MS);
        // Already in front (Home pressed while home was showing): release after the next frame.
        if (hasWindowFocus()) releaseOverlayAfterNextFrame();
    }

    @Override
    protected void onPause() {
        home.removeCallbacks(weatherTick);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) releaseOverlayAfterNextFrame();
    }

    private void releaseOverlayAfterNextFrame() {
        if (!HomeButtonService.isOverlayShowing()) return;
        releasePending = true;
        home.invalidate();
    }

    @Override
    protected void onDestroy() {
        getWindow().removeOnFrameMetricsAvailableListener(frameRendered);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // This is the home screen: there is nothing behind it.
    }

    @Override
    public void onMoreApps() {
        startActivity(new Intent(this, MoreAppsActivity.class));
    }

    @Override
    public void onOpen(int index, View tile) {
        AppCatalog.App app = AppCatalog.APPS[index];
        if (app.isSettings()) {
            openSettings(this);
            return;
        }
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(app.pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(app.pkg);
        if (intent == null) {
            Toast.makeText(this, app.name + " is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // The app grows out of its tile.
        Bundle zoom = ActivityOptions.makeScaleUpAnimation(
                tile, 0, 0, tile.getWidth(), tile.getHeight()).toBundle();
        try {
            startActivity(intent, zoom);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Can't open " + app.name, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens Fire TV settings directly, skipping the hop through Amazon's home screen. */
    static void openSettings(Context context) {
        HomeButtonService.noteSettingsLaunch();
        Intent direct = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS")
                .setPackage("com.amazon.tv.launcher");
        Intent[] candidates = {direct, new Intent(android.provider.Settings.ACTION_SETTINGS)};
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Toast.makeText(context, "Settings unavailable", Toast.LENGTH_SHORT).show();
    }
}
