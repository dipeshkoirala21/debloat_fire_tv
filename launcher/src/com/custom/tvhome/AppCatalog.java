package com.custom.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** The apps on the home screen, their banners, and which one has focus (shared by activity and overlay). */
final class AppCatalog {

    static final String SETTINGS = "settings";

    static final class App {
        final String pkg;
        final String name;
        final String subtitle;
        final int accent;

        App(String pkg, String name, String subtitle, int accent) {
            this.pkg = pkg;
            this.name = name;
            this.subtitle = subtitle;
            this.accent = accent;
        }

        boolean isSettings() {
            return SETTINGS.equals(pkg);
        }
    }

    static final App[] APPS = {
            new App("com.netflix.ninja", "Netflix", "Movies and series", 0xFFB20710),
            new App("org.smarttube.stable", "SmartTube", "YouTube, without the ads", 0xFFC62828),
            new App("com.amazon.firebat", "Prime Video", "Originals, movies and live sport", 0xFF0B79B8),
            new App("com.stremio.one", "Stremio", "Your streaming library", 0xFF6A4CF0),
            new App("com.amazon.firetv.youtube", "YouTube", "Videos, music and live", 0xFFE0261B),
            new App(SETTINGS, "Settings", "Network, display and remotes", 0xFF566070),
    };

    /** Index of the focused tile; the overlay draws the same state the activity will show. */
    static int focused = 0;

    private static final Drawable.ConstantState[] BANNERS = new Drawable.ConstantState[APPS.length];
    private static final boolean[] LOADED = new boolean[APPS.length];

    private AppCatalog() {
    }

    /** A fresh banner drawable (each view needs its own instance), or null if the app has none. */
    static Drawable banner(Context context, int index) {
        App app = APPS[index];
        if (app.isSettings()) return null;
        if (!LOADED[index]) {
            LOADED[index] = true;
            PackageManager pm = context.getPackageManager();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(app.pkg, 0);
                Drawable d = ai.loadBanner(pm);
                if (d == null) d = ai.loadIcon(pm);
                if (d != null) BANNERS[index] = d.getConstantState();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Drawable.ConstantState cs = BANNERS[index];
        return cs == null ? null : cs.newDrawable(context.getResources()).mutate();
    }
}
