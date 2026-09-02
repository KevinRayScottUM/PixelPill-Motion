package io.github.pixelpill.motion.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

public final class PillPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillBounds = new RectF();
    private float fraction = 1f, shrink = .76f, overshoot = .08f;
    private int pressMs = 120, releaseMs = 190;
    private ValueAnimator animator;
    private int gestureToken;

    public PillPreviewView(Context c) { this(c, null); }
    public PillPreviewView(Context c, AttributeSet a) { super(c, a); paint.setColor(0xfff2f0f7); setClickable(true); }
    public void configure(float s, int p, int r, float o) { shrink=s; pressMs=p; releaseMs=r; overshoot=o; invalidate(); }
    public void play() {
        int token = ++gestureToken;
        animateTo(shrink, pressMs, false);
        postDelayed(() -> {
            if (gestureToken == token) animateTo(1f, releaseMs, true);
        }, pressMs + 120L);
    }
    private void animateTo(float target, int duration, boolean spring) {
        if (animator != null) animator.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(fraction, target);
        animator = a;
        a.setDuration(Math.max(40, duration));
        if (spring) {
            a.setInterpolator(new OvershootInterpolator(
                    Math.max(.1f, Math.min(2.5f, overshoot * 10f))));
        } else {
            a.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        }
        a.addUpdateListener(v -> { fraction=(float)v.getAnimatedValue(); invalidate(); });
        a.start();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); float max=Math.min(getWidth()*.52f, dp(180)); float w=max*fraction; float h=dp(6);
        pillBounds.set((getWidth()-w)/2f,(getHeight()-h)/2f,(getWidth()+w)/2f,(getHeight()+h)/2f);
        c.drawRoundRect(pillBounds,h/2,h/2,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            ++gestureToken; animateTo(shrink,pressMs,false); return true;
        }
        if (e.getActionMasked()==MotionEvent.ACTION_UP
                || e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
            animateTo(1f,releaseMs,true);
            if (e.getActionMasked()==MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return true;
    }
    @Override protected void onDetachedFromWindow() {
        ++gestureToken;
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private float dp(float v) { return v*getResources().getDisplayMetrics().density; }
}
