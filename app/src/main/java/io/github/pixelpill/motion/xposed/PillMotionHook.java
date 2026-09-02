package io.github.pixelpill.motion.xposed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.pixelpill.motion.BuildConfig;
import io.github.pixelpill.motion.settings.HapticStrength;
import io.github.pixelpill.motion.settings.MotionConfig;

/** Defensive SystemUI + Pixel Fold taskbar gesture-handle motion hooks. */
public final class PillMotionHook implements IXposedHookLoadPackage {
    private static final String TAG = "PixelPillMotion";
    private static final Uri SETTINGS = Uri.parse("content://" + MotionConfig.AUTHORITY);
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String AOSP_LAUNCHER = "com.android.launcher3";

    private static final String[] HANDLE_CLASSES = {
            "com.android.systemui.navigationbar.gestural.NavigationHandle",
            "com.android.systemui.navigationbar.views.NavigationHandle",
            "com.android.systemui.navigationbar.NavigationHandle"
    };
    private static final String[] BAR_VIEW_CLASSES = {
            "com.android.systemui.navigationbar.views.NavigationBarView",
            "com.android.systemui.navigationbar.NavigationBarView"
    };

    private static final Map<View, HandleAnimationState> HANDLE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> LAUNCHER_PRESSED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<ArrayDeque<Integer>> CANVAS_SAVES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<LauncherMotion> LAUNCHER_MOTION = new ThreadLocal<>();
    private static final AtomicBoolean SETTINGS_RECEIVER_REGISTERED = new AtomicBoolean();
    private static final BroadcastReceiver SETTINGS_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (MotionConfig.ACTION_CHANGED.equals(intent.getAction())) {
                cacheAt = 0L;
                verbose("Settings cache invalidated");
            }
        }
    };
    private static volatile MotionConfig cached = new MotionConfig();
    private static volatile long cacheAt;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (SYSTEM_UI.equals(p.packageName)) {
            // Ignore screenshot/wallpaper auxiliary processes that share the package name.
            if (p.processName != null && !SYSTEM_UI.equals(p.processName)) return;
            int nativeHooks = hookSystemUiNativeHandle(p.classLoader);
            int drawHooks = hookSystemUiHandleDrawing(p.classLoader);
            int touchHooks = hookSystemUiTouch(p.classLoader);
            log("SystemUI installed: native=" + nativeHooks + ", draw=" + drawHooks
                    + ", touch=" + touchHooks, null);
        } else if (PIXEL_LAUNCHER.equals(p.packageName) || AOSP_LAUNCHER.equals(p.packageName)) {
            int hooks = hookLauncherTaskbar(p.classLoader);
            log("Launcher installed: hooks=" + hooks + ", process=" + p.processName, null);
        }
    }

    /** The device log proves this class/method exists even where ButtonDispatcher exposed no hook. */
    private static int hookSystemUiNativeHandle(ClassLoader loader) {
        int installed = 0;
        for (String name : HANDLE_CLASSES) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader); if (type == null) continue;
            int count = XposedBridge.hookAllMethods(type, "animateLongPress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View handle = (View) p.thisObject;
                        MotionConfig cfg = config(handle.getContext()); if (!cfg.enabled) return;
                        boolean down = boolArg(p.args, 0, false);
                        HandleAnimationState state = stateFor(handle);
                        boolean suppress;
                        synchronized (state) {
                            suppress = state.moduleOwnsAnimation
                                    || state.nativePressed == down;
                            if (!suppress) {
                                state.nativePressed = down;
                                state.nativeReleasing = !down;
                            }
                        }
                        if (suppress && canSkipAnimationMethod(p)) {
                            p.setResult(null);
                            verbose("SystemUI duplicate native animation suppressed: down=" + down);
                            return;
                        }
                        if (cfg.circleCompatible && p.args.length > 1 && p.args[1] instanceof Boolean) {
                            p.args[1] = true;
                        }
                        if (p.args.length > 2 && p.args[2] instanceof Number) {
                            p.args[2] = (long) Math.max(40,
                                    down ? cfg.pressDuration : cfg.releaseDuration);
                        }
                        if (down) scheduleNativeSafetyRelease(handle, cfg);
                        verbose("SystemUI call: down=" + down + ", shrink="
                                + boolArg(p.args, 1, false) + ", duration="
                                + numberArg(p.args, 2, -1));
                    } catch (Throwable t) { log("SystemUI native call left untouched", t); }
                }
            }).size();
            installed += count;
            log("SystemUI native method: " + name + " count=" + count, null);
        }
        return installed;
    }

    /** Customizes width for this draw only; no View property, Paint, alpha, or color is mutated. */
    private static int hookSystemUiHandleDrawing(ClassLoader loader) {
        int installed = 0;
        for (String name : HANDLE_CLASSES) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader); if (type == null) continue;
            int count = XposedBridge.hookAllMethods(type, "onDraw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int saveCount = -1;
                    try {
                        View handle = (View) p.thisObject; Canvas canvas = canvasArg(p.args);
                        MotionConfig cfg = config(handle.getContext());
                        HandleAnimationState state = stateFor(handle);
                        boolean moduleOwns; boolean nativeReleasing; float moduleScale;
                        synchronized (state) {
                            moduleOwns = state.moduleOwnsAnimation;
                            nativeReleasing = state.nativeReleasing;
                            moduleScale = state.drawScale;
                        }
                        if (canvas != null && cfg.enabled && moduleOwns
                                && handle.getWidth() > 0) {
                            saveCount = canvas.save();
                            canvas.scale(clamp(moduleScale, .45f, 1.25f), 1f,
                                    handle.getWidth() / 2f, 0f);
                        } else if (canvas != null && cfg.enabled && handle.getWidth() > 0) {
                            boolean shrink = XposedHelpers.getBooleanField(handle, "mShrink");
                            float progress = XposedHelpers.getFloatField(
                                    handle, "mPulseAnimationProgress");
                            if (shrink && progress > 0f) {
                                float nativeInset = XposedHelpers.getFloatField(
                                        handle, "mShrinkWidthForAnimation");
                                float nativeWidth = Math.max(1f,
                                        handle.getWidth() - 2f * nativeInset * progress);
                                float desiredRatio = 1f - (1f - ratio(cfg)) * progress;
                                if (nativeReleasing && cfg.overshoot > 0f) {
                                    desiredRatio += cfg.overshoot
                                            * (float) Math.sin(Math.PI * (1f - progress));
                                }
                                float desiredWidth = handle.getWidth()
                                        * Math.max(.45f, desiredRatio);
                                float drawScale = clamp(desiredWidth / nativeWidth, .55f, 1.35f);
                                saveCount = canvas.save();
                                canvas.scale(drawScale, 1f, handle.getWidth() / 2f, 0f);
                            }
                        }
                    } catch (Throwable t) { log("SystemUI draw customization skipped", t); }
                    CANVAS_SAVES.get().push(saveCount);
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ArrayDeque<Integer> saves = CANVAS_SAVES.get(); if (saves.isEmpty()) return;
                    int saveCount = saves.pop(); if (saveCount < 0) return;
                    try {
                        Canvas canvas = canvasArg(p.args);
                        if (canvas != null) canvas.restoreToCount(saveCount);
                    } catch (Throwable t) { log("SystemUI canvas restore failed", t); }
                }
            }).size();
            installed += count;
        }
        return installed;
    }

    /** Observes only touches near the handle; the original MotionEvent is never consumed. */
    private static int hookSystemUiTouch(ClassLoader loader) {
        int installed = 0;
        XC_MethodHook observer = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    MotionEvent event = motionArg(p.args); if (event == null) return;
                    Object dispatcher = callOrNull(p.thisObject, "getHomeHandle");
                    View handle = currentHandle(dispatcher); if (handle == null) return;
                    MotionConfig cfg = config(handle.getContext());
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        if (!cfg.enabled || cfg.longPressOnly || !cfg.animateTouch
                                || !insideHandle(event, handle)) return;
                        if (startModuleGesture(handle, cfg)) verbose("SystemUI ACTION_DOWN");
                    } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                            && finishModuleGesture(handle, cfg)) {
                        verbose("SystemUI ACTION_" + (action == MotionEvent.ACTION_UP
                                ? "UP" : "CANCEL"));
                    }
                } catch (Throwable t) { log("SystemUI touch observation skipped", t); }
            }
        };
        for (String name : BAR_VIEW_CLASSES) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader); if (type == null) continue;
            int count = XposedBridge.hookAllMethods(type, "onInterceptTouchEvent", observer).size();
            count += XposedBridge.hookAllMethods(type, "onTouchEvent", observer).size();
            installed += count;
            log("SystemUI touch methods: " + name + " count=" + count, null);
        }
        return installed;
    }

    /** Pixel Fold's unfolded/stashed navigation handle is rendered in Pixel Launcher. */
    private static int hookLauncherTaskbar(ClassLoader loader) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleViewController", loader);
        Class<?> handleType = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleView", loader);
        int installed = 0;
        if (controller != null) {
            int count = XposedBridge.hookAllMethods(controller, "animateNavBarLongPress",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                View handle = (View) XposedHelpers.getObjectField(
                                        p.thisObject, "mStashedHandleView");
                                MotionConfig cfg = config(handle.getContext()); if (!cfg.enabled) return;
                                boolean down = boolArg(p.args, 0, false);
                                if (cfg.circleCompatible && p.args.length > 1
                                        && p.args[1] instanceof Boolean) p.args[1] = true;
                                if (p.args.length > 2 && p.args[2] instanceof Number) {
                                    p.args[2] = (long) Math.max(40,
                                            down ? cfg.pressDuration : cfg.releaseDuration);
                                }
                                LAUNCHER_MOTION.set(new LauncherMotion(cfg, down));
                                verbose("Launcher call: down=" + down + ", shrink="
                                        + boolArg(p.args, 1, false));
                            } catch (Throwable t) { log("Launcher controller left untouched", t); }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            LAUNCHER_MOTION.remove();
                        }
                    }).size();
            installed += count;
            log("Launcher controller methods=" + count, null);
        } else log("Launcher controller class missing", null);

        if (handleType != null) {
            int count = XposedBridge.hookAllMethods(handleType, "animateScale", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    LauncherMotion motion = LAUNCHER_MOTION.get(); if (motion == null) return;
                    try {
                        if (p.args.length > 0 && p.args[0] instanceof Number) {
                            p.args[0] = motion.down ? ratio(motion.config) : 1f;
                        }
                        if (p.args.length > 1 && p.args[1] instanceof Number) {
                            p.args[1] = (long) Math.max(40, motion.down
                                    ? motion.config.pressDuration : motion.config.releaseDuration);
                        }
                    } catch (Throwable t) { log("Launcher scale left untouched", t); }
                }
            }).size();
            installed += count;
            log("Launcher scale methods=" + count, null);
        } else log("Launcher handle class missing", null);
        installed += hookLauncherInputHandler(loader);
        installed += hookLauncherInputConsumer(loader);
        return installed;
    }

    /**
     * Pixel builds can disable the AOSP animation through DeviceConfig. These callbacks are still
     * reached for every valid nav-handle press, so explicitly invoke the NavHandle interface here.
     */
    private static int hookLauncherInputHandler(ClassLoader loader) {
        Class<?> handler = XposedHelpers.findClassIfExists(
                "com.android.quickstep.inputconsumers.NavHandleLongPressHandler", loader);
        if (handler == null) {
            log("Launcher input handler class missing", null);
            return 0;
        }
        int started = XposedBridge.hookAllMethods(handler, "onTouchStarted", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.args.length == 0 || p.args[0] == null) return;
                try {
                     Context context = launcherContext(p.args[0]); if (context == null) return;
                    MotionConfig cfg = config(context); if (!cfg.enabled || cfg.longPressOnly
                            || !cfg.animateTouch) return;
                    driveLauncherNavHandle(p.args[0], true, cfg);
                    verbose("Launcher input: touch started");
                } catch (Throwable t) { log("Launcher touch-start animation skipped", t); }
            }
        }).size();
        int finished = XposedBridge.hookAllMethods(handler, "onTouchFinished", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || p.args[0] == null) return;
                try {
                    Context context = launcherContext(p.args[0]); if (context == null) return;
                    MotionConfig cfg = config(context);
                    driveLauncherNavHandle(p.args[0], false, cfg);
                    verbose("Launcher input: touch finished");
                } catch (Throwable t) { log("Launcher touch-finish animation skipped", t); }
            }
        }).size();
        log("Launcher input methods: started=" + started + ", finished=" + finished, null);
        return started + finished;
    }

    /** Lowest-level fallback: this consumer receives the actual Circle-to-Search touch stream. */
    private static int hookLauncherInputConsumer(ClassLoader loader) {
        Class<?> consumer = XposedHelpers.findClassIfExists(
                "com.android.quickstep.inputconsumers.NavHandleLongPressInputConsumer", loader);
        if (consumer == null) {
            log("Launcher input consumer class missing", null);
            return 0;
        }
        int count = XposedBridge.hookAllMethods(consumer, "onMotionEvent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    MotionEvent event = motionArg(p.args); if (event == null) return;
                    Object navHandle = XposedHelpers.getObjectField(p.thisObject, "mNavHandle");
                    if (navHandle == null) return;
                    Context context = launcherContext(navHandle); if (context == null) return;
                    MotionConfig cfg = config(context); if (!cfg.enabled) return;
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        if (cfg.longPressOnly || !cfg.animateTouch) return;
                        Object inside = XposedHelpers.callMethod(
                                p.thisObject, "isInNavBarHorizontalArea", event.getRawX());
                        if (inside instanceof Boolean && (Boolean) inside) {
                            driveLauncherNavHandle(navHandle, true, cfg);
                            verbose("Launcher consumer: ACTION_DOWN");
                        }
                    } else if ((action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL)
                            && Boolean.TRUE.equals(LAUNCHER_PRESSED.get(navHandle))) {
                        driveLauncherNavHandle(navHandle, false, cfg);
                        verbose("Launcher consumer: ACTION_" + (action == MotionEvent.ACTION_UP
                                ? "UP" : "CANCEL"));
                    }
                } catch (Throwable t) { log("Launcher input-consumer animation skipped", t); }
            }
        }).size();
        log("Launcher input consumer methods=" + count, null);
        return count;
    }

    private static void driveLauncherNavHandle(Object navHandle, boolean down, MotionConfig cfg) {
        Boolean old = LAUNCHER_PRESSED.get(navHandle); if (old != null && old == down) return;
        LAUNCHER_PRESSED.put(navHandle, down);
        invokeLauncherNavHandle(navHandle, down, cfg);
        if (down) performModuleHaptic(launcherContext(navHandle),
                launcherHandleView(navHandle), cfg);
    }

    private static void invokeLauncherNavHandle(Object navHandle, boolean down, MotionConfig cfg) {
        long duration = Math.max(40, down ? cfg.pressDuration : cfg.releaseDuration);
        XposedHelpers.callMethod(navHandle, "animateNavBarLongPress", down, true, duration);
    }

    private static Context launcherContext(Object navHandle) {
        if (navHandle instanceof View) return ((View) navHandle).getContext();
        try {
            Object view = XposedHelpers.getObjectField(navHandle, "mStashedHandleView");
            if (view instanceof View) return ((View) view).getContext();
        } catch (Throwable ignored) {}
        for (String field : new String[]{"mContext", "mApplicationContext"}) {
            try {
                Object value = XposedHelpers.getObjectField(navHandle, field);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    private static View launcherHandleView(Object navHandle) {
        if (navHandle instanceof View) return (View) navHandle;
        try {
            Object view = XposedHelpers.getObjectField(navHandle, "mStashedHandleView");
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Delivers one touch-class haptic without borrowing the native long-press semantic event. */
    private static void performModuleHaptic(Context context, View fallbackView, MotionConfig cfg) {
        if (context == null || !cfg.haptics || cfg.hapticStrength == HapticStrength.OFF) return;
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
        } catch (Throwable ignored) {}

        try {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            Vibrator vibrator = manager == null ? null : manager.getDefaultVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                int primitive = cfg.hapticStrength == HapticStrength.LIGHT
                        ? VibrationEffect.Composition.PRIMITIVE_TICK
                        : VibrationEffect.Composition.PRIMITIVE_CLICK;
                boolean[] supported = vibrator.arePrimitivesSupported(primitive);
                VibrationEffect effect;
                if (supported.length > 0 && supported[0]) {
                    effect = VibrationEffect.startComposition()
                            .addPrimitive(primitive, cfg.hapticStrength.primitiveScale)
                            .compose();
                } else {
                    effect = vibrator.hasAmplitudeControl()
                            ? VibrationEffect.createOneShot(
                                    cfg.hapticStrength.fallbackDurationMs,
                                    cfg.hapticStrength.fallbackAmplitude)
                            : VibrationEffect.createOneShot(
                                    cfg.hapticStrength.fallbackDurationMs,
                                    VibrationEffect.DEFAULT_AMPLITUDE);
                }
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(
                        VibrationAttributes.USAGE_TOUCH));
                return;
            }
        } catch (Throwable t) {
            verbose("Direct touch haptic unavailable: " + t.getClass().getSimpleName());
        }

        if (fallbackView == null) return;
        int feedback = cfg.hapticStrength == HapticStrength.LIGHT
                ? HapticFeedbackConstants.CLOCK_TICK
                : cfg.hapticStrength == HapticStrength.MEDIUM
                        ? HapticFeedbackConstants.CONTEXT_CLICK
                        : HapticFeedbackConstants.CONFIRM;
        try {
            fallbackView.performHapticFeedback(feedback);
        } catch (Throwable t) {
            verbose("View touch haptic unavailable: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Starts exactly one module-owned draw animation for an ordinary gesture. The NavigationHandle
     * View bounds, alpha, visibility, Paint, dark intensity, and navbar background remain untouched.
     */
    private static boolean startModuleGesture(View handle, MotionConfig cfg) {
        HandleAnimationState state = stateFor(handle);
        int gestureToken;
        synchronized (state) {
            if (state.gestureActive) return false;
            state.gestureActive = true;
            state.moduleOwnsAnimation = true;
            state.phase = AnimationPhase.PRESSING;
            gestureToken = ++state.gestureToken;
        }
        animateModuleScale(handle, state, ratio(cfg), cfg.pressDuration, false, cfg.overshoot);
        performModuleHaptic(handle.getContext(), handle, cfg);
        scheduleModuleSafetyRelease(handle, gestureToken);
        return true;
    }

    private static boolean finishModuleGesture(View handle, MotionConfig cfg) {
        HandleAnimationState state = stateFor(handle);
        synchronized (state) {
            if (!state.gestureActive) return false;
            state.gestureActive = false;
            state.phase = AnimationPhase.RETURNING;
        }
        animateModuleScale(handle, state, 1f, cfg.releaseDuration, true, cfg.overshoot);
        return true;
    }

    /** Animator replacement always begins at the current rendered scale, never a preset endpoint. */
    private static void animateModuleScale(View handle, HandleAnimationState state, float target,
            int duration, boolean returning, float overshoot) {
        ValueAnimator previous; float start; int token;
        synchronized (state) {
            previous = state.animator;
            state.animator = null;
            start = state.drawScale;
            token = ++state.animatorToken;
        }
        if (previous != null) previous.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(start, target);
        animator.setDuration(Math.max(40, duration));
        if (returning && overshoot > 0f) {
            animator.setInterpolator(new OvershootInterpolator(
                    clamp(overshoot * 10f, 0.1f, 2.5f)));
        } else {
            animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        }
        animator.addUpdateListener(valueAnimator -> {
            synchronized (state) {
                if (state.animatorToken != token) return;
                state.drawScale = (float) valueAnimator.getAnimatedValue();
            }
            handle.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                synchronized (state) {
                    if (state.animatorToken != token) return;
                    state.animator = null;
                    if (returning && !state.gestureActive) {
                        state.drawScale = 1f;
                        state.moduleOwnsAnimation = false;
                        state.phase = AnimationPhase.IDLE;
                    } else if (!returning && state.gestureActive) {
                        state.phase = AnimationPhase.PRESSED;
                    }
                }
                handle.invalidate();
            }
        });
        synchronized (state) {
            if (state.animatorToken != token) return;
            state.animator = animator;
        }
        animator.start();
    }

    private static void scheduleModuleSafetyRelease(View handle, int gestureToken) {
        handle.postDelayed(() -> {
            try {
                HandleAnimationState state = stateFor(handle);
                synchronized (state) {
                    if (!state.gestureActive || state.gestureToken != gestureToken) return;
                }
                finishModuleGesture(handle, config(handle.getContext()));
            } catch (Throwable t) { log("SystemUI module safety release skipped", t); }
        }, 1600L);
    }

    private static void scheduleNativeSafetyRelease(View handle, MotionConfig cfg) {
        long delay = Math.max(1400L, cfg.pressDuration + 1000L);
        handle.postDelayed(() -> {
            try {
                HandleAnimationState state = stateFor(handle);
                synchronized (state) {
                    if (!state.nativePressed || state.moduleOwnsAnimation) return;
                }
                XposedHelpers.callMethod(handle, "animateLongPress", false, true,
                        (long) Math.max(40, config(handle.getContext()).releaseDuration));
            } catch (Throwable t) { log("SystemUI native safety release skipped", t); }
        }, delay);
    }

    private static boolean insideHandle(MotionEvent e, View handle) {
        int[] pos = new int[2]; handle.getLocationOnScreen(pos);
        float density = handle.getResources().getDisplayMetrics().density;
        float xSlop = 28f * density, ySlop = 18f * density;
        return e.getRawX() >= pos[0] - xSlop
                && e.getRawX() <= pos[0] + handle.getWidth() + xSlop
                && e.getRawY() >= pos[1] - ySlop
                && e.getRawY() <= pos[1] + handle.getHeight() + ySlop;
    }

    private static View currentHandle(Object dispatcher) {
        if (dispatcher == null) return null;
        Object current = callOrNull(dispatcher, "getCurrentView");
        if (current instanceof View && isHandle((View) current)) return (View) current;
        try {
            Object raw = XposedHelpers.getObjectField(dispatcher, "mViews");
            if (raw instanceof List<?>) for (Object item : (List<?>) raw) {
                if (item instanceof View && isHandle((View) item)) return (View) item;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static MotionConfig config(Context context) {
        ensureSettingsReceiver(context);
        long now = SystemClock.uptimeMillis(); if (now - cacheAt < 1200) return cached;
        try {
            Bundle b = context.getContentResolver().call(SETTINGS, "get_config", null, null);
            cached = MotionConfig.from(b); cacheAt = now;
        } catch (Throwable t) { cacheAt = now; log("settings unavailable; defaults retained", t); }
        return cached;
    }

    private static void ensureSettingsReceiver(Context context) {
        if (context == null || SETTINGS_RECEIVER_REGISTERED.get()
                || !SETTINGS_RECEIVER_REGISTERED.compareAndSet(false, true)) return;
        try {
            Context application = context.getApplicationContext();
            (application == null ? context : application).registerReceiver(
                    SETTINGS_RECEIVER, new IntentFilter(MotionConfig.ACTION_CHANGED),
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            SETTINGS_RECEIVER_REGISTERED.set(false);
            verbose("Settings receiver unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static float ratio(MotionConfig c) {
        return clamp(c.shrinkRatio, .5f, .98f);
    }
    private static HandleAnimationState stateFor(View handle) {
        synchronized (HANDLE_STATES) {
            HandleAnimationState state = HANDLE_STATES.get(handle);
            if (state == null) {
                state = new HandleAnimationState();
                HANDLE_STATES.put(handle, state);
            }
            return state;
        }
    }
    private static boolean canSkipAnimationMethod(XC_MethodHook.MethodHookParam p) {
        return p.method instanceof Method && ((Method) p.method).getReturnType() == Void.TYPE;
    }
    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static boolean isHandle(View v) {
        String name = v.getClass().getName();
        return name.endsWith("NavigationHandle") || name.endsWith("QuickswitchOrientedNavHandle");
    }
    private static Object callOrNull(Object target, String method) {
        try { return XposedHelpers.callMethod(target, method); } catch (Throwable ignored) { return null; }
    }
    private static MotionEvent motionArg(Object[] args) { for (Object a : args) if (a instanceof MotionEvent) return (MotionEvent) a; return null; }
    private static Canvas canvasArg(Object[] args) { for (Object a : args) if (a instanceof Canvas) return (Canvas) a; return null; }
    private static boolean boolArg(Object[] args, int i, boolean fallback) { return args.length > i && args[i] instanceof Boolean ? (Boolean) args[i] : fallback; }
    private static long numberArg(Object[] args, int i, long fallback) { return args.length > i && args[i] instanceof Number ? ((Number) args[i]).longValue() : fallback; }
    private static void verbose(String message) {
        if (BuildConfig.VERBOSE_HOOK_LOGS) log(message, null);
    }
    private static void log(String message, Throwable t) {
        if (t == null) XposedBridge.log(TAG + ": " + message);
        else XposedBridge.log(TAG + ": " + message + " (" + t.getClass().getSimpleName() + ")");
    }
    private static final class LauncherMotion {
        final MotionConfig config; final boolean down;
        LauncherMotion(MotionConfig config, boolean down) { this.config = config; this.down = down; }
    }
    private enum AnimationPhase { IDLE, PRESSING, PRESSED, RETURNING }
    private static final class HandleAnimationState {
        ValueAnimator animator;
        float drawScale = 1f;
        boolean gestureActive;
        boolean moduleOwnsAnimation;
        boolean nativePressed;
        boolean nativeReleasing;
        int gestureToken;
        int animatorToken;
        AnimationPhase phase = AnimationPhase.IDLE;
    }
}
