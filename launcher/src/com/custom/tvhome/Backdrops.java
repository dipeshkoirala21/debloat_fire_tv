package com.custom.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Pre-rendered ambient backdrops, one per app: a dark base, a glow of the app's colour from the
 * top-right, a faint one bottom-left, and the scrims that keep text readable, all baked into a
 * single half-resolution bitmap. Drawing one filtered bitmap per frame is far cheaper on the
 * Fire TV GPU than five full-screen gradient layers.
 */
final class Backdrops {

    private static final int W = 960;
    private static final int H = 540;
    private static final Bitmap[] CACHE = new Bitmap[AppCatalog.APPS.length];

    private Backdrops() {
    }

    static synchronized Bitmap get(int index) {
        if (CACHE[index] == null) CACHE[index] = render(AppCatalog.APPS[index].accent);
        return CACHE[index];
    }

    /** Renders the rest in the background so focus changes never wait on them. */
    static void warmUp() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // prepareToDraw() uploads to the GPU ahead of time, so the first fade doesn't stutter.
                for (int i = 0; i < CACHE.length; i++) get(i).prepareToDraw();
            }
        }, "backdrop-warmup");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Bitmap render(int accent) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF07080C);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        p.setShader(new RadialGradient(W * 0.82f, 0f, W * 0.85f,
                new int[]{withAlpha(accent, 0xA6), withAlpha(accent, 0x33), withAlpha(accent, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        p.setShader(new RadialGradient(W * 0.08f, H, W * 0.6f,
                withAlpha(accent, 0x40), withAlpha(accent, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        // Bottom scrim behind the dock and left scrim behind the title.
        p.setShader(new LinearGradient(0, H, 0, H - 330,
                new int[]{0xF2050608, 0x99050608, 0x00050608}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, H - 330, W, H, p);
        p.setShader(new LinearGradient(0, 0, 620, 0, 0x8C050608, 0x00050608, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 620, H, p);
        return bmp;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
