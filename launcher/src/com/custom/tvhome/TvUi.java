package com.custom.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small shared pieces for secondary screens, using the home's existing slate and typography. */
final class TvUi {
    static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String value, int size, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        t.setTypeface(Typeface.create(size >= 26 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static LinearLayout page(Context c, String title, String subtitle) {
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(c, 64), dp(c, 38), dp(c, 64), dp(c, 32));
        BitmapDrawable bg = new BitmapDrawable(c.getResources(), Backdrops.get(AppCatalog.APPS.length - 1));
        bg.setFilterBitmap(true);
        page.setBackground(bg);
        page.addView(text(c, title, 38, Color.WHITE));
        TextView description = text(c, subtitle, 17, 0xB3FFFFFF);
        description.setPadding(0, dp(c, 10), 0, dp(c, 26));
        page.addView(description);
        return page;
    }

    static GradientDrawable surface(Context c, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0xFF343C4B : 0xFF202630);
        d.setCornerRadius(dp(c, 12));
        d.setStroke(dp(c, focused ? 2 : 1), focused ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    static void focusable(View view) {
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setClickable(true);
        view.setStateListAnimator(null);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setBackground(surface(view.getContext(), false));
        view.setOnFocusChangeListener((v, focus) -> {
            v.setBackground(surface(v.getContext(), focus));
            // Keep bounds stable: TV focus rings must never grow into a clipped parent.
            v.setScaleX(1f);
            v.setScaleY(1f);
            if (v instanceof Button) {
                GradientDrawable shape = surface(v.getContext(), focus);
                shape.setColor(focus ? 0xFFF4F5F7 : 0xFF29313E);
                v.setBackground(shape);
                ((Button) v).setTextColor(focus ? 0xFF151A22 : Color.WHITE);
            }
        });
    }

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setIncludeFontPadding(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        b.setGravity(Gravity.CENTER);
        focusable(b);
        return b;
    }
}
