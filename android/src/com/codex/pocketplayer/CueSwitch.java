package com.codex.pocketplayer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Switch;

final class CueSwitch {
    static void style(Switch control, int accent, int onAccent, int raised, int dim) {
        float density = control.getResources().getDisplayMetrics().density;
        int height = Math.round(30 * density), width = Math.round(50 * density);
        int[] checked = {android.R.attr.state_checked};
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(checked, thumb(onAccent, height, 12 * density));
        thumb.addState(new int[0], thumb(dim, height, 12 * density));
        StateListDrawable track = new StateListDrawable();
        track.addState(checked, track(accent, width, height));
        track.addState(new int[0], track(Appearance.mix(raised, dim, .2), width, height));
        control.setThumbDrawable(thumb);
        control.setTrackDrawable(track);
        control.setThumbTintList(null);
        control.setTrackTintList(null);
        control.setShowText(false);
        control.setSplitTrack(false);
        control.setSwitchMinWidth(width);
    }

    private static Drawable thumb(int color, int size, float radius) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void draw(Canvas canvas) {
                paint.setColor(color);
                canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), radius, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static GradientDrawable track(int color, int width, int height) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(color);
        track.setCornerRadius(height / 2f);
        track.setSize(width, height);
        return track;
    }

    private CueSwitch() { }
}
