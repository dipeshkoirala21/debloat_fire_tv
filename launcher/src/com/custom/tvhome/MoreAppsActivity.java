package com.custom.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Extra apps and utilities live here; the original six-tile home stays uncluttered. */
public class MoreAppsActivity extends Activity {
    private SharedPreferences preferences;
    private boolean picking;
    private View firstPickerTile;
    private View firstShortcutTile;
    private View addAppsTile;
    private CleanerControl cleaner;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("extra_apps", MODE_PRIVATE);
        showApps();
    }

    private int dp(int n) { return TvUi.dp(this, n); }

    private List<ResolveInfo> installedApps() {
        PackageManager pm = getPackageManager();
        HashMap<String, ResolveInfo> found = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo app : pm.queryIntentActivities(intent, 0)) {
                if (app.activityInfo != null && app.activityInfo.enabled
                        && !getPackageName().equals(app.activityInfo.packageName)) {
                    found.put(app.activityInfo.packageName, app);
                }
            }
        }
        ArrayList<ResolveInfo> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparing(a -> a.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private Set<String> selected() {
        return new HashSet<>(preferences.getStringSet("packages", new HashSet<String>()));
    }

    private void showApps() {
        picking = false;
        firstShortcutTile = null;
        LinearLayout page = TvUi.page(this, "More Apps", "Your apps and home controls");
        LinearLayout utilities = new LinearLayout(this);
        utilities.setClipChildren(false);
        utilities.setPadding(dp(4), dp(4), dp(4), dp(16));
        View worker = utility("RAM cleaner", "Closes background apps", 0,
                v -> showCleaner());
        View vacuum = utility("Roborock S5", "Open Valetudo", 1,
                v -> startActivity(new Intent(this, VacuumActivity.class)));
        View add = utility("Add apps", "Choose from your Fire TV", 2, v -> showPicker());
        addAppsTile = add;
        for (View v : new View[]{worker, vacuum, add}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(104), 1f);
            if (v != worker) lp.leftMargin = dp(22);
            utilities.addView(v, lp);
        }
        page.addView(utilities);
        TextView label = TvUi.text(this, "Your apps", 20, Color.WHITE);
        label.setPadding(dp(4), dp(8), 0, dp(14));
        page.addView(label);
        Set<String> packages = selected();
        ArrayList<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : installedApps()) {
            if (packages.contains(info.activityInfo.packageName)) apps.add(info);
        }
        if (apps.isEmpty()) {
            TextView empty = TvUi.text(this, "Use + to add apps here. Your home dock stays the same.", 17, 0xB3FFFFFF);
            empty.setPadding(dp(4), dp(10), 0, 0);
            page.addView(empty);
        } else {
            addGrid(page, apps, false);
            TextView manageHint = TvUi.text(this, "Hold Select or press Menu on an app to remove its shortcut", 13, 0xB3FFFFFF);
            manageHint.setPadding(dp(4), dp(8), 0, 0);
            page.addView(manageHint);
        }
        setContentView(page);
        worker.requestFocus();
    }

    private View utility(String title, String subtitle, int icon, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(20), dp(18), dp(20), dp(18));
        TvUi.focusable(tile);
        tile.setContentDescription(title + ". " + subtitle);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconParams.rightMargin = dp(18);
        tile.addView(new UtilityIcon(icon), iconParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = TvUi.text(this, title, 20, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        TextView detail = TvUi.text(this, subtitle, 13, 0xB3FFFFFF);
        detail.setPadding(0, dp(7), 0, 0);
        detail.setMaxLines(2);
        copy.addView(detail);
        tile.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        tile.setOnClickListener(click);
        return tile;
    }

    private void showPicker() {
        picking = true;
        firstPickerTile = null;
        LinearLayout page = TvUi.page(this, "Add apps", "Select to add or remove an app · Back when you’re done");
        List<ResolveInfo> apps = installedApps();
        if (apps.isEmpty()) page.addView(TvUi.text(this, "No launchable apps found", 18, Color.WHITE));
        else addGrid(page, apps, true);
        setContentView(page);
        if (firstPickerTile != null) firstPickerTile.requestFocus();
    }

    private void addGrid(LinearLayout page, List<ResolveInfo> apps, boolean picker) {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(4), dp(4), dp(4), dp(8));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        Set<String> chosen = selected();
        for (int i = 0; i < apps.size(); i++) {
            ResolveInfo app = apps.get(i);
            String pkg = app.activityInfo.packageName;
            String label = app.loadLabel(getPackageManager()).toString();
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(8), dp(8), dp(8), dp(10));
            TvUi.focusable(tile);
            if (picker && i == 0) firstPickerTile = tile;
            if (!picker && i == 0) firstShortcutTile = tile;
            if (!picker) {
                tile.setOnLongClickListener(v -> { showRemoveShortcut(pkg, label); return true; });
                tile.setOnKeyListener((v, key, event) -> {
                    if (key != KeyEvent.KEYCODE_MENU) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showRemoveShortcut(pkg, label);
                    return true;
                });
            }
            ImageView image = new ImageView(this);
            Drawable art = app.activityInfo.loadBanner(getPackageManager());
            if (art == null) art = app.loadIcon(getPackageManager());
            image.setImageDrawable(art);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new LinearLayout.LayoutParams(dp(120), dp(66)));
            TextView name = TvUi.text(this, label, 14, Color.WHITE);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(9), 0, 0);
            tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView state = TvUi.text(this, chosen.contains(pkg) ? "Added ✓" : "Add", 12, 0xB3FFFFFF);
            state.setPadding(0, dp(5), 0, 0);
            if (picker) tile.addView(state);
            tile.setContentDescription(label + (picker && chosen.contains(pkg) ? ", added" : ""));
            tile.setOnClickListener(v -> {
                if (picker) {
                    Set<String> set = selected();
                    if (!set.remove(pkg)) set.add(pkg);
                    preferences.edit().putStringSet("packages", set).apply();
                    state.setText(set.contains(pkg) ? "Added ✓" : "Add");
                    tile.setContentDescription(label + (set.contains(pkg) ? ", added" : ", not added"));
                } else launch(pkg, label);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(148);
            lp.height = dp(picker ? 132 : 114);
            lp.setMargins(0, 0, dp(i % 5 == 4 ? 0 : 18), dp(18));
            grid.addView(tile, lp);
        }
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void launch(String pkg, String label) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) {
            try { startActivity(intent); return; } catch (ActivityNotFoundException ignored) { }
        }
        Toast.makeText(this, label + " is no longer available", Toast.LENGTH_SHORT).show();
    }

    private void showRemoveShortcut(String pkg, String label) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(26), dp(28), dp(26));
        content.setBackground(TvUi.surface(this, false));
        content.addView(TvUi.text(this, label, 26, Color.WHITE));
        TextView message = TvUi.text(this, "Remove this shortcut from More Apps? The app will stay installed on your Fire TV.", 16, 0xCCFFFFFF);
        message.setPadding(0, dp(14), 0, dp(24));
        content.addView(message);
        LinearLayout actions = new LinearLayout(this);
        Button back = TvUi.button(this, "Keep");
        back.setOnClickListener(v -> dialog.dismiss());
        actions.addView(back, new LinearLayout.LayoutParams(dp(110), dp(50)));
        Button remove = TvUi.button(this, "Remove shortcut");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(50), 1);
        rlp.leftMargin = dp(14);
        actions.addView(remove, rlp);
        remove.setOnClickListener(v -> {
            Set<String> packages = selected();
            packages.remove(pkg);
            preferences.edit().putStringSet("packages", packages).apply();
            dialog.dismiss();
            showApps();
            (firstShortcutTile == null ? addAppsTile : firstShortcutTile).requestFocus();
            Toast.makeText(this, label + " shortcut removed", Toast.LENGTH_SHORT).show();
        });
        content.addView(actions);
        dialog.setContentView(content);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        dialog.getWindow().setLayout(dp(500), ViewGroup.LayoutParams.WRAP_CONTENT);
        remove.requestFocus();
    }

    private void showCleaner() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(32), dp(28), dp(32), dp(28));
        content.setBackground(TvUi.surface(this, false));
        content.addView(TvUi.text(this, "RAM cleaner", 28, Color.WHITE));
        TextView description = TvUi.text(this, "Closes background apps automatically when you return home.", 17, 0xCCFFFFFF);
        description.setPadding(0, dp(12), 0, dp(20));
        content.addView(description);
        TextView status = TvUi.text(this, "Checking worker…", 16, Color.WHITE);
        status.setMinHeight(dp(84));
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        android.graphics.drawable.GradientDrawable statusBackground = new android.graphics.drawable.GradientDrawable();
        statusBackground.setColor(0xFF151A22);
        statusBackground.setCornerRadius(dp(10));
        status.setBackground(statusBackground);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        Button restart = TvUi.button(this, "Restart worker");
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(22), 0, 0);
        Button back = TvUi.button(this, "Back");
        back.setOnClickListener(v -> dialog.dismiss());
        actions.addView(back, new LinearLayout.LayoutParams(dp(120), dp(50)));
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        restartParams.leftMargin = dp(14);
        actions.addView(restart, restartParams);
        content.addView(actions);
        cleaner = new CleanerControl(this, (busy, message) -> {
            status.setText(message);
            restart.setText(busy ? "Working…" : "Restart worker");
        });
        restart.setOnClickListener(v -> cleaner.restart());
        dialog.setContentView(content);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setOnDismissListener(d -> { if (cleaner != null) cleaner.close(); cleaner = null; });
        dialog.show();
        dialog.getWindow().setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        restart.requestFocus();
        cleaner.check();
    }

    @Override public void onBackPressed() {
        if (picking) showApps(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (cleaner != null) cleaner.close();
        super.onDestroy();
    }

    private final class UtilityIcon extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        UtilityIcon(int kind) { super(MoreAppsActivity.this); this.kind = kind; }
        @Override protected void onDraw(Canvas canvas) {
            float s = getWidth() / 30f;
            canvas.save(); canvas.scale(s, s);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (kind == 0) {
                canvas.drawArc(new RectF(4, 4, 26, 26), 35, 290, false, paint);
                canvas.drawLine(26, 3, 26, 11, paint); canvas.drawLine(18, 11, 26, 11, paint);
            } else if (kind == 1) {
                canvas.drawCircle(15, 15, 12, paint); canvas.drawCircle(15, 10, 4, paint);
                canvas.drawLine(11, 22, 19, 22, paint);
            } else {
                canvas.drawLine(15, 4, 15, 26, paint); canvas.drawLine(4, 15, 26, 15, paint);
            }
            canvas.restore();
        }
    }
}
