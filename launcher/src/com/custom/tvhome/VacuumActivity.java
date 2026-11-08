package com.custom.tvhome;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The real local Valetudo interface, with a remote-driven pointer for its touch controls. */
public class VacuumActivity extends Activity {
    private WebView web;
    private Viewport viewport;
    private boolean fitView = true;
    private float webScale = 1f;
    private Pointer pointer;
    private TextView hint;
    private TextView loading;
    private LocalVacuum discovery;
    private String origin;
    private boolean scrollMode;
    private boolean failed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF10151D);
        page.setPadding(dp(32), dp(18), dp(32), dp(22));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));
        header.addView(TvUi.text(this, "Roborock S5", 21, Color.WHITE));
        hint = TvUi.text(this, "Arrows move · OK clicks · Menu scrolls · Play/Pause zooms · Back", 13, 0xB3FFFFFF);
        hint.setGravity(Gravity.END);
        hint.setSingleLine(true);
        hint.setPadding(dp(16), 0, 0, 0);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);
        viewport = new Viewport();
        FrameLayout browser = viewport;
        web = new WebView(this);
        web.setBackgroundColor(0xFF121212);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (origin == null) return true;
                Uri allowed = Uri.parse(origin);
                // This is a local vacuum control, not a general browser. Never follow external links.
                return !allowed.getScheme().equals(url.getScheme()) || !allowed.getHost().equals(url.getHost())
                        || allowed.getPort() != url.getPort();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!failed) { loading.setVisibility(View.GONE); pointer.setVisibility(View.VISIBLE); }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) showError();
            }
        });
        browser.addView(web, new FrameLayout.LayoutParams(-1, -1));
        pointer = new Pointer();
        pointer.setVisibility(View.INVISIBLE);
        browser.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        loading = TvUi.text(this, "Finding your vacuum…", 22, Color.WHITE);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xFF10151D);
        browser.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        page.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        connect();
    }

    private int dp(int n) { return TvUi.dp(this, n); }
    private void connect() {
        failed = false;
        origin = null;
        loading.setText("Finding your vacuum…");
        loading.setVisibility(View.VISIBLE);
        pointer.setVisibility(View.INVISIBLE);
        if (discovery != null) discovery.close();
        discovery = new LocalVacuum(this, new LocalVacuum.Callback() {
            @Override public void found(String address) {
                origin = address;
                loading.setText("Opening Valetudo…");
                web.loadUrl(address + "/#/");
            }
            @Override public void failed() { showError(); }
        });
    }

    private void showError() {
        failed = true;
        loading.setText("Vacuum unavailable\nCheck that it is on the same Wi-Fi.\nPress Select to retry.");
        loading.setVisibility(View.VISIBLE);
        pointer.setVisibility(View.INVISIBLE);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                fitView = !fitView;
                viewport.requestLayout();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                scrollMode = !scrollMode;
                hint.setText(scrollMode ? "Arrows scroll · Menu moves pointer · Back returns"
                        : "Arrows move · OK clicks · Menu scrolls · Play/Pause zooms · Back");
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (failed) connect();
                else if (pointer.getVisibility() == View.VISIBLE) {
                    long time = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, pointer.x / webScale, pointer.y / webScale, 0);
                    MotionEvent up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, pointer.x / webScale, pointer.y / webScale, 0);
                    web.dispatchTouchEvent(down); web.dispatchTouchEvent(up);
                    down.recycle(); up.recycle();
                }
            }
            return true;
        }
        int dx = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        int dy = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
        if (dx != 0 || dy != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && pointer.getVisibility() == View.VISIBLE) {
                int step = dp(event.getRepeatCount() > 8 ? 30 : 12);
                if (scrollMode) {
                    // Scroll the DOM element beneath the pointer, including Valetudo's inner panels.
                    // elementFromPoint needs CSS coordinates, not screen pixels. Calculate
                    // against the live viewport so zoom never offsets the scroll target.
                    web.evaluateJavascript("(function(){let x=" + (pointer.x / Math.max(1, pointer.getWidth()))
                            + "*innerWidth,y=" + (pointer.y / Math.max(1, pointer.getHeight()))
                            + "*innerHeight,e=document.elementFromPoint(x,y);while(e){let s=getComputedStyle(e);"
                            + "if((/auto|scroll/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+2)"
                            + "||(/auto|scroll/.test(s.overflowX)&&e.scrollWidth>e.clientWidth+2)){e.scrollBy("
                            + (dx * 90) + "," + (dy * 90) + ");return;}e=e.parentElement;}window.scrollBy("
                            + (dx * 90) + "," + (dy * 90) + ");})()", null);
                } else {
                    pointer.x = Math.max(dp(6), Math.min(pointer.getWidth() - dp(6), pointer.x + dx * step));
                    pointer.y = Math.max(dp(6), Math.min(pointer.getHeight() - dp(6), pointer.y + dy * step));
                    pointer.invalidate();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        if (discovery != null) discovery.close();
        web.stopLoading(); web.destroy();
        super.onDestroy();
    }

    /** A real larger browser viewport scaled uniformly into the safe TV area.
     * CSS vh/vw and fixed-position panels now see the entire available page, rather
     * than an oversized page being clipped by the native toolbar or overscan.
     */
    private final class Viewport extends FrameLayout {
        private int virtualWidth, virtualHeight;
        Viewport() { super(VacuumActivity.this); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            webScale = Math.min(1f, Math.min(width / (float) dp(fitView ? 1280 : 1024),
                    height / (float) dp(fitView ? 640 : 540)));
            webScale = Math.max(0.1f, webScale);
            virtualWidth = Math.round(width / webScale);
            virtualHeight = Math.round(height / webScale);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(child == web ? virtualWidth : width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child == web ? virtualHeight : height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.layout(0, 0, child == web ? virtualWidth : r - l, child == web ? virtualHeight : b - t);
            }
            web.setPivotX(0); web.setPivotY(0);
            web.setScaleX(webScale); web.setScaleY(webScale);
        }
    }

    private final class Pointer extends View {
        float x, y;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pointer() { super(VacuumActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { x = w / 2f; y = h / 2f; }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(0x99000000);
            canvas.drawCircle(x, y, dp(8), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(6), paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, dp(1), paint);
        }
    }
}
