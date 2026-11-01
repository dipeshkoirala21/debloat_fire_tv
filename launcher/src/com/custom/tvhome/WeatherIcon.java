package com.custom.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Thin white line icons for the weather conditions, drawn on a 24-unit grid. */
final class WeatherIcon extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cloud = new Path();
    private final RectF oval = new RectF();
    private Weather.Kind kind = Weather.Kind.CLEAR;
    private boolean day = true;

    WeatherIcon(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xEBFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xEBFFFFFF);
    }

    void set(Weather.Kind kind, boolean day) {
        if (this.kind == kind && this.day == day) return;
        this.kind = kind;
        this.day = day;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        canvas.save();
        canvas.scale(s, s);
        stroke.setStrokeWidth(1.6f);
        switch (kind) {
            case CLEAR:
                if (day) sun(canvas, 12, 12, 4.2f, 2.2f);
                else moon(canvas, 12, 12, 7f);
                break;
            case PARTLY_CLOUDY:
                // Sun (or moon) peeking out behind the cloud.
                cloudPath(14, 16, 0.8f);
                canvas.save();
                clipOut(canvas, cloud);
                if (day) sun(canvas, 9, 9, 3.2f, 1.6f);
                else moon(canvas, 9, 9, 5f);
                canvas.restore();
                canvas.drawPath(cloud, stroke);
                break;
            case CLOUDY:
                cloudPath(12, 15, 1f);
                canvas.drawPath(cloud, stroke);
                break;
            case FOG:
                cloudPath(12, 10.5f, 0.8f);
                canvas.drawPath(cloud, stroke);
                canvas.drawLine(4, 17, 20, 17, stroke);
                canvas.drawLine(6, 20.5f, 18, 20.5f, stroke);
                break;
            case RAIN:
                cloudPath(12, 11, 0.9f);
                canvas.drawPath(cloud, stroke);
                for (int i = 0; i < 3; i++) canvas.drawLine(8.5f + i * 3.5f, 17, 7.5f + i * 3.5f, 20.5f, stroke);
                break;
            case SNOW:
                cloudPath(12, 11, 0.9f);
                canvas.drawPath(cloud, stroke);
                for (int i = 0; i < 3; i++) {
                    canvas.drawCircle(8 + i * 4, i == 1 ? 20 : 18, 1.1f, fill);
                }
                break;
            default: // THUNDER
                cloudPath(12, 10.5f, 0.9f);
                canvas.drawPath(cloud, stroke);
                path.reset();
                path.moveTo(12.8f, 15);
                path.lineTo(10, 19);
                path.lineTo(12.4f, 19);
                path.lineTo(11.2f, 22.5f);
                path.lineTo(14.6f, 17.8f);
                path.lineTo(12.2f, 17.8f);
                path.close();
                canvas.drawPath(path, fill);
                break;
        }
        canvas.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, float ray) {
        c.drawCircle(cx, cy, r, stroke);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float inner = r + 1.8f;
            c.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * (inner + ray), cy + sin * (inner + ray), stroke);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path disc = new Path();
        disc.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        disc.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(disc, stroke);
    }

    /** A cloud outline centred on (cx, cy), about 16 units wide at scale 1. */
    private void cloudPath(float cx, float cy, float scale) {
        cloud.reset();
        Path p = new Path();
        p.addCircle(cx - 3.2f * scale, cy, 3.6f * scale, Path.Direction.CW);
        cloud.op(p, Path.Op.UNION);
        p.reset();
        p.addCircle(cx + 1f * scale, cy - 2.4f * scale, 4.6f * scale, Path.Direction.CW);
        cloud.op(p, Path.Op.UNION);
        p.reset();
        p.addCircle(cx + 4.6f * scale, cy + 0.6f * scale, 3f * scale, Path.Direction.CW);
        cloud.op(p, Path.Op.UNION);
        p.reset();
        oval.set(cx - 6.8f * scale, cy, cx + 7.6f * scale, cy + 3.6f * scale);
        p.addRoundRect(oval, 1.8f * scale, 1.8f * scale, Path.Direction.CW);
        cloud.op(p, Path.Op.UNION);
    }

    @SuppressWarnings("deprecation")
    private static void clipOut(Canvas c, Path p) {
        if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(p);
        else c.clipPath(p, android.graphics.Region.Op.DIFFERENCE);
    }
}
