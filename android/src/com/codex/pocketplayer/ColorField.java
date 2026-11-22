package com.codex.pocketplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

final class ColorField extends View {
    interface Listener { void change(float horizontal, float vertical); }
    private final boolean strip;
    private final float scale;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final Path clip = new Path();
    private Shader horizontal, vertical;
    private float hue, saturation, value;
    Listener listener;

    ColorField(Context context, boolean strip) {
        super(context);
        this.strip = strip;
        scale = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setContentDescription(strip ? UiText.text(context, "Тон") : UiText.text(context, "Насыщенность и яркость"));
    }

    void show(float[] hsv) {
        hue = hsv[0]; saturation = hsv[1]; value = hsv[2];
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        area.set(scale, scale, width - scale, height - scale);
        clip.reset();
        clip.addRoundRect(area, 12 * scale, 12 * scale, Path.Direction.CW);
        int[] colors = strip ? new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}
            : new int[]{Color.WHITE, 0x00ffffff};
        horizontal = new LinearGradient(area.left, 0, area.right, 0, colors, null, Shader.TileMode.CLAMP);
        if (!strip) vertical = new LinearGradient(0, area.top, 0, area.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        if (!strip) {
            paint.setColor(Color.HSVToColor(new float[]{hue, 1, 1}));
            canvas.drawRect(area, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setShader(horizontal);
        canvas.drawRect(area, paint);
        if (vertical != null) {
            paint.setShader(vertical);
            canvas.drawRect(area, paint);
        }
        canvas.restore();
        paint.setShader(null);
        float margin = 8 * scale;
        float x = area.left + margin + (area.width() - 2 * margin) * (strip ? hue / 360 : saturation);
        float y = strip ? area.centerY() : area.top + margin + (area.height() - 2 * margin) * (1 - value);
        paint.setColor(Color.BLACK);
        canvas.drawCircle(x, y, 8 * scale, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 7 * scale, paint);
        paint.setColor(Color.HSVToColor(new float[]{hue, strip ? 1 : saturation, strip ? 1 : value}));
        canvas.drawCircle(x, y, 5 * scale, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (listener == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_UP:
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_MOVE: break;
            default: return true;
        }
        float inset = 9 * scale;
        listener.change(clamp((event.getX() - inset) / Math.max(1, getWidth() - 2 * inset)),
            1 - clamp((event.getY() - inset) / Math.max(1, getHeight() - 2 * inset)));
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (listener != null && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            listener.change(clamp((strip ? hue / 360 : saturation) + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -.02f : .02f)), value);
            return true;
        }
        if (listener != null && !strip && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            listener.change(saturation, clamp(value + (key == KeyEvent.KEYCODE_DPAD_DOWN ? -.02f : .02f)));
            return true;
        }
        return super.onKeyDown(key, event);
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
