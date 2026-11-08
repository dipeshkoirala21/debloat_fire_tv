package com.custom.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The home screen UI: an ambient backdrop tinted by the focused app, a large title, the clock,
 * and a dock of app tiles that lift, brighten and catch a light sheen when focused.
 *
 * The same view is drawn by HomeActivity (interactive) and by HomeButtonService's overlay
 * (static), so the overlay can cover Amazon's home screen with an identical frame.
 */
final class HomeView extends FrameLayout implements Weather.Listener {

    interface Listener {
        void onOpen(int index, View tile);
        void onMoreApps();
    }

    private static final int TILE_W = 128;
    private static final int TILE_H = 72;
    private static final int TILE_GAP = 22;
    private static final int CORNER = 12;
    private static final float FOCUS_SCALE = 1.12f;
    private static final float IDLE_DIM = 0.42f;

    private static final Interpolator EASE_OUT = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator LIFT = new OvershootInterpolator(1.1f);

    private final Listener listener;
    private final boolean interactive;
    private final FrameLayout backdrops;
    // backdrop[1] always sits above backdrop[0]; whichever is showing is fully opaque.
    private final View[] backdrop = new View[2];
    private final Drawable[] ambients = new Drawable[AppCatalog.APPS.length];
    private boolean topShowing;
    private boolean fading;
    private int backdropShown = -1;
    private int backdropWanted = -1;
    private final Runnable applyBackdrop = new Runnable() {
        @Override
        public void run() {
            if (!fading && backdropWanted != backdropShown) crossfadeTo(backdropWanted);
        }
    };
    private final LinearLayout titleBlock;
    private final TextView title;
    private final TextView subtitle;
    private final LinearLayout dock;
    private final FrameLayout[] tiles = new FrameLayout[AppCatalog.APPS.length];
    private final View[] dims = new View[AppCatalog.APPS.length];
    private final View[] sheens = new View[AppCatalog.APPS.length];
    private android.widget.Button moreApps;
    private static boolean moreFocused;
    private int shown = -1;
    private boolean animate = true;
    private final LinearLayout weatherBlock;
    private final WeatherIcon weatherIcon;
    private final TextView temperature;
    private final TextView conditions;
    private final LinearLayout outlookPill;
    private final WeatherIcon outlookIcon;
    private final TextView outlookText;
    private Weather.Now shownWeather;
    // Keeps "Rain in 45 min" counting down between weather refreshes.
    private final Runnable outlookTick = new Runnable() {
        @Override
        public void run() {
            if (shownWeather != null) {
                String text = shownWeather.outlook(System.currentTimeMillis());
                if (!java.util.Objects.equals(text, outlookShown())) applyOutlook(shownWeather, text);
            }
            postDelayed(this, 30 * 1000);
        }
    };

    HomeView(Context context, boolean interactive, Listener listener) {
        super(context);
        this.interactive = interactive;
        this.listener = listener;
        setClipChildren(false);

        // Ambient backdrop: two opaque layers that crossfade when focus rests on a new app.
        Backdrops.warmUp();
        backdrops = new FrameLayout(context);
        for (int i = 0; i < 2; i++) {
            backdrop[i] = new View(context);
            // Fading an opaque view needs no offscreen layer.
            backdrop[i].forceHasOverlappingRendering(false);
            backdrops.addView(backdrop[i], match());
        }
        addView(backdrops, match());

        // Clock and date, top-left: the first thing you read from the couch.
        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 58);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setLetterSpacing(-0.02f);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(dp(12), 0, dp(1), 0x66000000);
        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        date.setPadding(dp(3), dp(10), 0, 0);
        date.setShadowLayer(dp(8), 0, dp(1), 0x66000000);
        LinearLayout timeBlock = new LinearLayout(context);
        timeBlock.setOrientation(LinearLayout.VERTICAL);
        timeBlock.addView(clock);
        timeBlock.addView(date);
        LayoutParams tblp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tblp.setMargins(dp(64), dp(40), 0, 0);
        addView(timeBlock, tblp);

        // Weather, top-right: icon and temperature, conditions and city, then a rain/snow heads-up.
        weatherIcon = new WeatherIcon(context);
        temperature = new TextView(context);
        temperature.setTextColor(Color.WHITE);
        temperature.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temperature.setIncludeFontPadding(false);
        temperature.setShadowLayer(dp(12), 0, dp(1), 0x66000000);
        temperature.setPadding(dp(12), 0, 0, 0);
        LinearLayout tempRow = new LinearLayout(context);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);
        tempRow.addView(weatherIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        tempRow.addView(temperature);
        conditions = new TextView(context);
        conditions.setTextColor(0xCCFFFFFF);
        conditions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        conditions.setSingleLine(true);
        conditions.setPadding(0, dp(8), 0, 0);
        conditions.setShadowLayer(dp(8), 0, dp(1), 0x66000000);

        outlookIcon = new WeatherIcon(context);
        outlookText = new TextView(context);
        outlookText.setTextColor(Color.WHITE);
        outlookText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        outlookText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        outlookText.setSingleLine(true);
        outlookText.setPadding(dp(6), 0, 0, 0);
        outlookPill = new LinearLayout(context);
        outlookPill.setOrientation(LinearLayout.HORIZONTAL);
        outlookPill.setGravity(Gravity.CENTER_VERTICAL);
        outlookPill.setPadding(dp(12), dp(6), dp(14), dp(6));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(16));
        pill.setColor(0x2EFFFFFF);
        outlookPill.setBackground(pill);
        outlookPill.addView(outlookIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        outlookPill.addView(outlookText);
        outlookPill.setVisibility(GONE);

        weatherBlock = new LinearLayout(context);
        weatherBlock.setOrientation(LinearLayout.VERTICAL);
        weatherBlock.setGravity(Gravity.END);
        weatherBlock.addView(tempRow, wrap());
        weatherBlock.addView(conditions, wrap());
        LinearLayout.LayoutParams plp = wrap();
        plp.topMargin = dp(12);
        weatherBlock.addView(outlookPill, plp);
        LayoutParams wblp = new LayoutParams(dp(380), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        wblp.setMargins(0, dp(46), dp(56), 0);
        addView(weatherBlock, wblp);
        showWeather(Weather.current(context), false);

        // Title of the focused app.
        titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-0.015f);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setShadowLayer(dp(16), 0, dp(2), 0x80000000);
        subtitle = new TextView(context);
        subtitle.setTextColor(0xB3FFFFFF);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        subtitle.setPadding(dp(2), dp(10), 0, 0);
        subtitle.setSingleLine(true);
        // Fixed width: swapping the text never re-measures the screen mid-animation.
        titleBlock.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        titleBlock.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LayoutParams tlp = new LayoutParams(dp(720), LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tlp.setMargins(dp(64), 0, 0, dp(186));
        addView(titleBlock, tlp);

        // Dock of tiles.
        dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(20), dp(20), dp(20), dp(20));
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = buildTile(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H));
            if (i > 0) lp.leftMargin = dp(TILE_GAP);
            dock.addView(tiles[i], lp);
        }
        for (int i = 0; i < tiles.length; i++) {
            FrameLayout t = tiles[i];
            t.setNextFocusUpId(t.getId());
            t.setNextFocusDownId(t.getId());
            if (i == 0) t.setNextFocusLeftId(t.getId());
            if (i == tiles.length - 1) t.setNextFocusRightId(t.getId());
        }
        LayoutParams dlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(44);
        addView(dock, dlp);

        moreApps = new android.widget.Button(context);
        moreApps.setId(View.generateViewId());
        moreApps.setText("More Apps >");
        moreApps.setAllCaps(false);
        moreApps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        moreApps.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        moreApps.setPaintFlags(moreApps.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        moreApps.setPadding(0, 0, 0, 0);
        moreApps.setMinWidth(0);
        moreApps.setMinHeight(0);
        moreApps.setStateListAnimator(null);
        moreApps.setBackgroundColor(Color.TRANSPARENT);
        moreApps.setFocusable(interactive);
        moreApps.setClickable(interactive);
        if (android.os.Build.VERSION.SDK_INT >= 26) moreApps.setDefaultFocusHighlightEnabled(false);
        // Anchor to the Settings tile, including the dock's inset. Nothing else in the dock moves.
        LayoutParams mlp = new LayoutParams(dp(TILE_W), dp(32), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.leftMargin = dp((TILE_W + TILE_GAP) * (tiles.length - 1) / 2);
        mlp.bottomMargin = dp(22);
        addView(moreApps, mlp);
        FrameLayout settings = tiles[tiles.length - 1];
        settings.setNextFocusDownId(moreApps.getId());
        moreApps.setNextFocusUpId(settings.getId());
        moreApps.setNextFocusDownId(moreApps.getId());
        moreApps.setNextFocusLeftId(moreApps.getId());
        moreApps.setNextFocusRightId(moreApps.getId());
        moreApps.setOnFocusChangeListener((v, focused) -> {
            moreFocused = focused;
            moreApps.setTextColor(focused ? Color.WHITE : 0xB3FFFFFF);
            moreApps.setScaleX(focused ? 1.08f : 1f);
            moreApps.setScaleY(focused ? 1.08f : 1f);
        });
        if (interactive) moreApps.setOnClickListener(v -> { if (listener != null) listener.onMoreApps(); });
        moreApps.setTextColor(moreFocused ? Color.WHITE : 0xB3FFFFFF);
        moreApps.setScaleX(moreFocused ? 1.08f : 1f);
        moreApps.setScaleY(moreFocused ? 1.08f : 1f);
        showApp(AppCatalog.focused, false);
        for (int i = 0; i < tiles.length; i++) setLifted(i, !moreFocused && i == AppCatalog.focused, false);
    }

    /** Moves focus to a tile without animating, so the screen matches the overlay frame exactly. */
    void focusInstantly(int index) {
        animate = false;
        if (moreFocused) moreApps.requestFocus();
        else tiles[index].requestFocus();
        animate = true;
        showApp(index, false);
        for (int i = 0; i < tiles.length; i++) setLifted(i, !moreFocused && i == index, false);
    }

    /** First appearance after boot: the dock and title rise into place. */
    void playEntrance() {
        dock.setAlpha(0f);
        dock.setTranslationY(dp(28));
        dock.animate().alpha(1f).translationY(0).setDuration(520).setStartDelay(60)
                .setInterpolator(EASE_OUT).start();
        titleBlock.setAlpha(0f);
        titleBlock.setTranslationY(dp(16));
        titleBlock.animate().alpha(1f).translationY(0).setDuration(520).setStartDelay(160)
                .setInterpolator(EASE_OUT).start();
    }

    private FrameLayout buildTile(final int index) {
        Context c = getContext();
        AppCatalog.App app = AppCatalog.APPS[index];
        final FrameLayout tile = new FrameLayout(c);
        tile.setId(View.generateViewId());
        tile.setFocusable(interactive);
        if (android.os.Build.VERSION.SDK_INT >= 26) tile.setDefaultFocusHighlightEnabled(false);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(CORNER));
            }
        });

        Drawable banner = AppCatalog.banner(c, index);
        if (app.isSettings()) {
            tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF3B414D, 0xFF22262E}));
            ImageView gear = new ImageView(c);
            gear.setImageResource(R.drawable.ic_settings);
            gear.setAlpha(0.92f);
            tile.addView(gear, new LayoutParams(dp(30), dp(30), Gravity.CENTER));
        } else if (banner != null) {
            tile.setBackgroundColor(0xFF1C1F26);
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            tile.addView(image, match());
        } else {
            tile.setBackgroundColor(0xFF1C1F26);
            TextView name = new TextView(c);
            name.setText(app.name);
            name.setTextColor(Color.WHITE);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, match());
        }

        // Diagonal light sheen that sweeps across when the tile gains focus.
        View sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        sheen.setRotation(18f);
        sheen.setVisibility(INVISIBLE);
        tile.addView(sheen, new LayoutParams(dp(56), dp(TILE_H * 2), Gravity.CENTER_VERTICAL));
        sheens[index] = sheen;

        // Dimming layer: unfocused tiles recede, the focused one is full brightness.
        View dim = new View(c);
        dim.setBackgroundColor(Color.BLACK);
        dim.forceHasOverlappingRendering(false);
        tile.addView(dim, match());
        dims[index] = dim;

        if (interactive) {
            tile.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    setLifted(index, hasFocus, animate);
                    if (hasFocus) {
                        moreFocused = false;
                        AppCatalog.focused = index;
                        showApp(index, animate);
                    }
                }
            });
            tile.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onOpen(index, v);
                }
            });
        }
        return tile;
    }

    private void setLifted(int index, boolean lifted, boolean animated) {
        FrameLayout tile = tiles[index];
        View dim = dims[index];
        float scale = lifted ? FOCUS_SCALE : 1f;
        float dimAlpha = lifted ? 0f : IDLE_DIM;
        tile.animate().cancel();
        dim.animate().cancel();
        if (!animated) {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            dim.setAlpha(dimAlpha);
            return;
        }
        tile.animate().scaleX(scale).scaleY(scale)
                .setDuration(lifted ? 340 : 240)
                .setInterpolator(lifted ? LIFT : EASE_OUT).start();
        dim.animate().alpha(dimAlpha).setDuration(lifted ? 200 : 300).setInterpolator(EASE_OUT).start();
        if (lifted) sweepSheen(index);
    }

    private void sweepSheen(int index) {
        final View sheen = sheens[index];
        sheen.animate().cancel();
        sheen.setTranslationX(-dp(80));
        sheen.setVisibility(VISIBLE);
        sheen.animate().translationX(dp(TILE_W + 40)).setStartDelay(140).setDuration(820)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        sheen.setVisibility(INVISIBLE);
                    }
                }).start();
    }

    private void showApp(final int index, boolean animated) {
        if (index == shown) return;
        shown = index;
        final AppCatalog.App app = AppCatalog.APPS[index];

        // Backdrop follows once focus rests, like Apple TV; fast scrolling costs no full-screen fades.
        backdropWanted = index;
        removeCallbacks(applyBackdrop);
        if (animated) postDelayed(applyBackdrop, 110);
        else setBackdropNow(index);

        // Title: slide out, swap text, slide in.
        titleBlock.animate().cancel();
        if (!animated) {
            title.setText(app.name);
            subtitle.setText(app.subtitle);
            titleBlock.setAlpha(1f);
            titleBlock.setTranslationX(0);
            titleBlock.setTranslationY(0);
            return;
        }
        titleBlock.animate().alpha(0f).translationX(-dp(10)).translationY(0).setStartDelay(0).setDuration(110)
                .setInterpolator(EASE_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        title.setText(app.name);
                        subtitle.setText(app.subtitle);
                        titleBlock.setTranslationX(dp(14));
                        titleBlock.setTranslationY(0);
                        titleBlock.animate().alpha(1f).translationX(0).setDuration(320)
                                .setInterpolator(EASE_OUT).start();
                    }
                }).start();
    }

    private void setBackdropNow(int index) {
        View bottom = backdrop[0];
        View top = backdrop[1];
        bottom.animate().cancel();
        top.animate().cancel();
        bottom.setBackground(ambient(index));
        bottom.setAlpha(1f);
        bottom.setVisibility(VISIBLE);
        top.setAlpha(0f);
        top.setVisibility(INVISIBLE);
        topShowing = false;
        fading = false;
        backdropShown = index;
    }

    /** Crossfades to an app's backdrop without ever repainting a layer that is still on screen. */
    private void crossfadeTo(final int index) {
        final View bottom = backdrop[0];
        final View top = backdrop[1];
        fading = true;
        Runnable done = new Runnable() {
            @Override
            public void run() {
                fading = false;
                backdropShown = index;
                if (backdropWanted != backdropShown) post(applyBackdrop);
            }
        };
        if (topShowing) {
            bottom.setBackground(ambient(index));
            bottom.setAlpha(1f);
            bottom.setVisibility(VISIBLE);
            top.animate().alpha(0f).setDuration(480).setInterpolator(EASE_OUT)
                    .withEndAction(chain(new Runnable() {
                        @Override
                        public void run() {
                            top.setVisibility(INVISIBLE);
                            topShowing = false;
                        }
                    }, done)).start();
        } else {
            top.setBackground(ambient(index));
            top.setAlpha(0f);
            top.setVisibility(VISIBLE);
            top.animate().alpha(1f).setDuration(480).setInterpolator(EASE_OUT)
                    .withEndAction(chain(new Runnable() {
                        @Override
                        public void run() {
                            bottom.setVisibility(INVISIBLE);
                            topShowing = true;
                        }
                    }, done)).start();
        }
    }

    private static Runnable chain(final Runnable a, final Runnable b) {
        return new Runnable() {
            @Override
            public void run() {
                a.run();
                b.run();
            }
        };
    }

    private Drawable ambient(int index) {
        if (ambients[index] == null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), Backdrops.get(index));
            d.setFilterBitmap(true);
            ambients[index] = d;
        }
        return ambients[index];
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Weather.addListener(this);
        // A reading may have arrived while detached.
        showWeather(Weather.current(getContext()), false);
        removeCallbacks(outlookTick);
        postDelayed(outlookTick, 30 * 1000);
    }

    @Override
    protected void onDetachedFromWindow() {
        Weather.removeListener(this);
        removeCallbacks(outlookTick);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWeather(Weather.Now now) {
        showWeather(now, interactive && isShown());
    }

    private void showWeather(final Weather.Now now, boolean animated) {
        if (now == null) {
            weatherBlock.setVisibility(INVISIBLE);
            return;
        }
        final String temp = now.temperature + "\u00B0";
        final String line = now.place.isEmpty() ? now.condition() : now.condition() + " \u00B7 " + now.place;
        final String outlook = now.outlook(System.currentTimeMillis());
        boolean unchanged = weatherBlock.getVisibility() == VISIBLE && shownWeather != null
                && shownWeather.kind == now.kind && shownWeather.day == now.day
                && temp.contentEquals(temperature.getText()) && line.contentEquals(conditions.getText())
                && java.util.Objects.equals(outlook, outlookShown());
        shownWeather = now;
        if (unchanged) return;
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                weatherIcon.set(now.kind, now.day);
                temperature.setText(temp);
                conditions.setText(line);
                applyOutlook(now, outlook);
            }
        };
        weatherBlock.animate().cancel();
        if (!animated) {
            apply.run();
            weatherBlock.setAlpha(1f);
            weatherBlock.setVisibility(VISIBLE);
            return;
        }
        weatherBlock.animate().alpha(0f).setDuration(weatherBlock.getVisibility() == VISIBLE ? 200 : 0)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        apply.run();
                        weatherBlock.setVisibility(VISIBLE);
                        weatherBlock.animate().alpha(1f).setDuration(400).setInterpolator(EASE_OUT).start();
                    }
                }).start();
    }

    private void applyOutlook(Weather.Now now, String text) {
        if (text == null) {
            outlookPill.setVisibility(GONE);
            return;
        }
        outlookIcon.set(now.change == Weather.Kind.SNOW ? Weather.Kind.SNOW : Weather.Kind.RAIN, true);
        outlookText.setText(text);
        outlookPill.setVisibility(VISIBLE);
    }

    private String outlookShown() {
        return outlookPill.getVisibility() == VISIBLE ? outlookText.getText().toString() : null;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (interactive && moreApps.hasFocus() && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) tiles[tiles.length - 1].requestFocus();
            return true;
        }
        return interactive && super.dispatchKeyEvent(event);
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
