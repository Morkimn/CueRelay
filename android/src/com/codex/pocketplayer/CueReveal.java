package com.codex.pocketplayer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class CueReveal {
    static void replace(FrameLayout host, View next, Point origin, int height) {
        View old = host.getChildCount() == 0 ? null : host.getChildAt(host.getChildCount() - 1);
        if (origin == null || old == null || old.getWidth() == 0 || old.getHeight() == 0 ||
            !ValueAnimator.areAnimatorsEnabled()) {
            host.removeAllViews();
            host.addView(next, new FrameLayout.LayoutParams(-1, height));
            return;
        }
        Bitmap oldFrame = Bitmap.createBitmap(old.getWidth(), old.getHeight(), Bitmap.Config.ARGB_8888);
        old.draw(new Canvas(oldFrame));
        ImageView previous = new ImageView(host.getContext());
        previous.setImageBitmap(oldFrame);
        previous.setScaleType(ImageView.ScaleType.FIT_XY);
        previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.removeAllViews();
        host.addView(previous, new FrameLayout.LayoutParams(old.getWidth(), old.getHeight()));
        next.setVisibility(View.INVISIBLE);
        host.addView(next, new FrameLayout.LayoutParams(-1, height));
        next.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (!next.isAttachedToWindow()) return true;
                next.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] location = new int[2];
                next.getLocationOnScreen(location);
                int x = Math.max(0, Math.min(next.getWidth(), origin.x - location[0]));
                int y = Math.max(0, Math.min(next.getHeight(), origin.y - location[1]));
                float radius = (float) Math.hypot(Math.max(x, next.getWidth() - x), Math.max(y, next.getHeight() - y));
                next.setVisibility(View.VISIBLE);
                Animator animation = ViewAnimationUtils.createCircularReveal(next, x, y, 0, radius);
                animation.setDuration(520);
                animation.setInterpolator(new DecelerateInterpolator(1.7f));
                animation.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animator) { host.removeView(previous); }
                });
                animation.start();
                return true;
            }
        });
    }

    static Point center(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Point(location[0] + view.getWidth() / 2, location[1] + view.getHeight() / 2);
    }

    private CueReveal() { }
}
