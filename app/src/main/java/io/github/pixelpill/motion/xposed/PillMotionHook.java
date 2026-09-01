package io.github.pixelpill.motion.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.pixelpill.motion.BuildConfig;
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

    private static final Map<View, Boolean> PRESSED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> RELEASING = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> LAUNCHER_PRESSED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<ArrayDeque<Integer>> CANVAS_SAVES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<LauncherMotion> LAUNCHER_MOTION = new ThreadLocal<>();
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
                        if (cfg.circleCompatible && p.args.length > 1 && p.args[1] instanceof Boolean) {
                            p.args[1] = true;
                        }
                        if (p.args.length > 2 && p.args[2] instanceof Number) {
                            p.args[2] = (long) Math.max(40,
                                    down ? cfg.pressDuration : cfg.releaseDuration);
                        }
                        PRESSED.put(handle, down); RELEASING.put(handle, !down);
                        if (down) scheduleSafetyRelease(handle, cfg);
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
                        boolean shrink = XposedHelpers.getBooleanField(handle, "mShrink");
                        float progress = XposedHelpers.getFloatField(handle, "mPulseAnimationProgress");
                        if (canvas != null && cfg.enabled && shrink && progress > 0f
                                && handle.getWidth() > 0) {
                            float nativeInset = XposedHelpers.getFloatField(
                                    handle, "mShrinkWidthForAnimation");
                            float nativeWidth = Math.max(1f,
                                    handle.getWidth() - 2f * nativeInset * progress);
                            float desiredRatio = 1f - (1f - ratio(cfg)) * progress;
                            if (Boolean.TRUE.equals(RELEASING.get(handle)) && cfg.overshoot > 0f) {
                                desiredRatio += cfg.overshoot
                                        * (float) Math.sin(Math.PI * (1f - progress));
                            }
                            float desiredWidth = handle.getWidth() * Math.max(.45f, desiredRatio);
                            float drawScale = Math.max(.55f,
                                    Math.min(1.35f, desiredWidth / nativeWidth));
                            saveCount = canvas.save();
                            canvas.scale(drawScale, 1f, handle.getWidth() / 2f, 0f);
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
                        driveSystemUi(handle, true, cfg);
                    } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                            && Boolean.TRUE.equals(PRESSED.get(handle))) {
                        driveSystemUi(handle, false, cfg);
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
                    MotionConfig cfg = config(context); if (!cfg.enabled) return;
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

    private static void driveSystemUi(View handle, boolean down, MotionConfig cfg) {
        Boolean old = PRESSED.get(handle); if (old != null && old == down) return;
        PRESSED.put(handle, down); RELEASING.put(handle, !down);
        long duration = Math.max(40, down ? cfg.pressDuration : cfg.releaseDuration);
        XposedHelpers.callMethod(handle, "animateLongPress", down, true, duration);
        if (down && cfg.haptics && cfg.hapticStrength > 0) {
            int kind = cfg.hapticStrength == 1 ? HapticFeedbackConstants.CLOCK_TICK
                    : cfg.hapticStrength == 2 ? HapticFeedbackConstants.CONTEXT_CLICK
                    : HapticFeedbackConstants.LONG_PRESS;
            handle.performHapticFeedback(kind);
        }
    }

    private static void scheduleSafetyRelease(View handle, MotionConfig cfg) {
        long delay = Math.max(1400L, cfg.pressDuration + 1000L);
        handle.postDelayed(() -> {
            try {
                if (Boolean.TRUE.equals(PRESSED.get(handle))) driveSystemUi(handle, false, cfg);
            } catch (Throwable t) { log("SystemUI safety release skipped", t); }
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
        long now = SystemClock.uptimeMillis(); if (now - cacheAt < 1200) return cached;
        try {
            Bundle b = context.getContentResolver().call(SETTINGS, "get_config", null, null);
            cached = MotionConfig.from(b); cacheAt = now;
        } catch (Throwable t) { cacheAt = now; log("settings unavailable; defaults retained", t); }
        return cached;
    }

    private static float ratio(MotionConfig c) {
        if ("subtle".equals(c.mode)) return Math.max(c.shrinkRatio, .84f);
        if ("spring".equals(c.mode)) return Math.min(c.shrinkRatio, .74f);
        return Math.max(.5f, Math.min(.98f, c.shrinkRatio));
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
}
