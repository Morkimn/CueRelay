package com.codex.pocketplayer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class WaveDrawable extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final int active, inactive;

    WaveDrawable(int active, int inactive) {
        this.active = active;
        this.inactive = inactive;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        float y = bounds.exactCenterY(), width = bounds.width();
        float edge = width * getLevel() / 10000f;
        float gap = bounds.height() * .91f;
        stroke.setStrokeWidth(Math.max(2, bounds.height() * .28f));
        stroke.setColor(inactive);
        if (edge + gap < width) canvas.drawLine(bounds.left + edge + gap, y, bounds.right, y, stroke);
        float end = edge - gap;
        if (end <= 0) return;
        stroke.setColor(active);
        stroke.setStrokeWidth(Math.max(2, bounds.height() * .36f));
        float amplitude = bounds.height() * .26f;
        float wavelength = Math.max(12f, bounds.height() * 2.1f);
        wave.reset();
        wave.moveTo(bounds.left, y);
        for (float x = 1; x < end; x++) {
            float fade = Math.min(1, Math.min(x, end - x) / (bounds.height() * .5f));
            wave.lineTo(bounds.left + x, y + amplitude * fade * (float)Math.sin(x / wavelength * 2 * Math.PI));
        }
        wave.lineTo(bounds.left + end, y);
        canvas.drawPath(wave, stroke);
    }

    @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
