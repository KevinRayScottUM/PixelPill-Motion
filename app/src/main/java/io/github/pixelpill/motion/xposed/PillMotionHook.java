package io.github.pixelpill.motion.xposed;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.pixelpill.motion.BuildConfig;
import io.github.pixelpill.motion.runtime.GestureCycle;
import io.github.pixelpill.motion.settings.HapticStrength;
import io.github.pixelpill.motion.settings.MotionConfig;

/** Defensive SystemUI + Pixel Fold taskbar gesture-handle motion hooks. */
public final class PillMotionHook implements IXposedHookLoadPackage {
    private static final String TAG = "PixelPillMotion";
    private static final Uri SETTINGS = Uri.parse("content://" + MotionConfig.AUTHORITY);
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String AOSP_LAUNCHER = "com.android.launcher3";
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;

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
    private static final Map<View, LauncherHandleAnimationState> LAUNCHER_HANDLE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean SETTINGS_RECEIVER_REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean CONFIG_REFRESH_IN_FLIGHT = new AtomicBoolean();
    private static final AtomicInteger CONFIG_FAILURE_LOG_BUDGET = new AtomicInteger(3);
    private static final long CONFIG_REFRESH_INTERVAL_MS = 5000L;
    private static final BroadcastReceiver SETTINGS_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (MotionConfig.ACTION_CHANGED.equals(intent.getAction())) {
                cacheAt = 0L;
                refreshConfigAsync(context);
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
            int touchHooks = hookSystemUiTouch(p.classLoader);
            if (BuildConfig.VERBOSE_HOOK_LOGS) RuntimeDiagnostics.hookSystemUi(p.classLoader);
            log("SystemUI installed: native=" + nativeHooks + ", touch=" + touchHooks, null);
        } else if (PIXEL_LAUNCHER.equals(p.packageName) || AOSP_LAUNCHER.equals(p.packageName)) {
            int hooks = hookLauncherTaskbar(p.classLoader);
            if (BuildConfig.VERBOSE_HOOK_LOGS) RuntimeDiagnostics.hookLauncher(p.classLoader);
            log("Launcher installed: hooks=" + hooks + ", process=" + p.processName, null);
        }
    }

    /** Owns the SystemUI visual animation regardless of whether Quickstep or SystemUI arrives first. */
    private static int hookSystemUiNativeHandle(ClassLoader loader) {
        int installed = 0;
        for (String name : HANDLE_CLASSES) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader); if (type == null) continue;
            int count = XposedBridge.hookAllMethods(type, "animateLongPress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View handle = (View) p.thisObject;
                        MotionConfig cfg = config(handle.getContext());
                        if (!ownsSystemUiAnimation(cfg)) return;
                        boolean down = boolArg(p.args, 0, false);
                        HandleAnimationState state = stateFor(handle);
                        boolean changed = down
                                ? startModuleGesture(handle, cfg, true)
                                : finishModuleGesture(handle, cfg);
                        boolean moduleOwns;
                        synchronized (state) {
                            moduleOwns = state.moduleOwnsAnimation;
                        }
                        if ((changed || moduleOwns) && canSkipAnimationMethod(p)) {
                            p.setResult(null);
                            verbose("SystemUI native visual routed to module state: down=" + down);
                            return;
                        }
                    } catch (Throwable t) { log("SystemUI native call left untouched", t); }
                }
            }).size();
            count += XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { primeSettings(((View) p.thisObject).getContext()); }
                    catch (Throwable t) { verbose("Settings prewarm skipped"); }
                }
            }).size();
            installed += count;
            log("SystemUI handle lifecycle: " + name + " count=" + count, null);
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
                        if (startModuleGesture(handle, cfg, false)) verbose("SystemUI ACTION_DOWN");
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

    /** Pixel Fold's active phone-mode navigation handle is rendered in Pixel Launcher. */
    private static int hookLauncherTaskbar(ClassLoader loader) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleViewController", loader);
        Class<?> handleType = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleView", loader);
        Class<?> samplingType = XposedHelpers.findClassIfExists(
                "com.android.wm.shell.shared.handles.RegionSamplingHelper", loader);
        int installed = 0;
        if (controller != null) {
            int count = XposedBridge.hookAllMethods(controller, "animateNavBarLongPress",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                View handle = (View) XposedHelpers.getObjectField(
                                        p.thisObject, "mStashedHandleView");
                                MotionConfig cfg = config(handle.getContext());
                                if (!cfg.enabled) {
                                    cleanupLauncherHandle(handle, p.thisObject);
                                    return;
                                }
                                boolean down = boolArg(p.args, 0, false);
                                boolean shrink = boolArg(p.args, 1, false);
                                Boolean previous = LAUNCHER_PRESSED.get(p.thisObject);
                                if (previous != null && previous == down
                                        && ownsLauncherDrawing(p.thisObject, handle)
                                        && canSkipAnimationMethod(p)) {
                                    p.setResult(null);
                                    verbose("Duplicate local taskbar animation suppressed");
                                    return;
                                }
                                LAUNCHER_PRESSED.put(p.thisObject, down);
                                if (down) performModuleHaptic(handle.getContext(), handle, cfg);
                                if (!isActiveLauncherHandle(p.thisObject, handle)) {
                                    cleanupLauncherHandle(handle, p.thisObject);
                                    if (canSkipAnimationMethod(p)) p.setResult(null);
                                    verbose("Inactive taskbar handle rejected: down=" + down);
                                    return;
                                }
                                LauncherHandleAnimationState animationState = launcherStateFor(
                                        p.thisObject, handle);
                                if (!down && deferNativeLauncherRelease(
                                        p.thisObject, animationState, p)) {
                                    verbose("Native taskbar release held behind system-state barrier");
                                    return;
                                }
                                if (down) cancelDeferredLauncherRelease(animationState);
                                float target = down ? (shrink ? ratio(cfg) : 1.18f) : 1f;
                                boolean owned = animateLauncherScaleX(p.thisObject, handle,
                                        target, down ? cfg.pressDuration : cfg.releaseDuration,
                                        !down, cfg.overshoot);
                                if (owned && canSkipAnimationMethod(p)) p.setResult(null);
                                verbose("Launcher scale-x call: down=" + down + ", shrink="
                                        + shrink + ", target=" + target + ", owned=" + owned);
                            } catch (Throwable t) { log("Launcher controller left untouched", t); }
                        }
                    }).size();
            installed += count;
            log("Launcher controller methods=" + count, null);

            count = XposedBridge.hookAllMethods(controller, "updateSamplingState",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                View handle = (View) XposedHelpers.getObjectField(
                                        p.thisObject, "mStashedHandleView");
                                MotionConfig cfg = config(handle.getContext());
                                if (cfg.enabled && isActiveLauncherHandle(p.thisObject, handle)) {
                                    LauncherHandleAnimationState state = launcherStateFor(
                                            p.thisObject, handle);
                                    ensureContinuityOverlay(p.thisObject, handle, state);
                                    syncContinuityOverlayVisibility(
                                            p.thisObject, handle, state);
                                    maybeStartDeferredLauncherRelease(
                                            p.thisObject, handle, state, cfg,
                                            true, false);
                                } else if (!cfg.enabled) {
                                    cleanupLauncherHandle(handle, p.thisObject);
                                }
                            } catch (Throwable t) {
                                verbose("Taskbar surface cache policy skipped: "
                                        + t.getClass().getSimpleName());
                            }
                        }
                    }).size();
            installed += count;
            log("Launcher surface-cache lifecycle methods=" + count, null);

        } else log("Launcher controller class missing", null);

        if (handleType != null) {
            int count = XposedBridge.hookAllMethods(handleType, "updateSampledRegion",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                stabilizeLauncherSampledRegion((View) p.thisObject, p.args);
                            } catch (Throwable t) {
                                verbose("Taskbar sampled bounds compensation skipped: "
                                        + t.getClass().getSimpleName());
                            }
                        }
                    }).size();
            installed += count;
            log("Launcher sampled-bounds methods=" + count, null);

            count = XposedBridge.hookAllMethods(handleType, "updateHandleColor",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!canSkipAnimationMethod(p) || p.args.length == 0
                                    || !(p.args[0] instanceof Boolean)) return;
                            try {
                                View handle = (View) p.thisObject;
                                LauncherHandleAnimationState state;
                                synchronized (LAUNCHER_HANDLE_STATES) {
                                    state = LAUNCHER_HANDLE_STATES.get(handle);
                                }
                                if (state == null) return;
                                boolean requestedDark = (Boolean) p.args[0];
                                boolean defer = false;
                                synchronized (state) {
                                    if (state.colorFrozen) {
                                        state.pendingRegionDark = requestedDark;
                                        defer = true;
                                    } else if (state.postRecoveryColorGuard) {
                                        state.postRecoveryColorGuard = false;
                                        defer = state.baselineRegionDark != null
                                                && requestedDark
                                                != state.baselineRegionDark.booleanValue();
                                        state.baselineRegionDark = null;
                                        state.pendingRegionDark = null;
                                    }
                                }
                                if (defer) {
                                    p.setResult(null);
                                    verbose("Transient taskbar color update suppressed");
                                } else {
                                    updateContinuityOverlayColor(handle, state,
                                            requestedDark, boolArg(p.args, 1, false));
                                }
                                boolean continuityActive;
                                synchronized (state) {
                                    continuityActive = state.continuityOverlay != null
                                            && state.continuityOverlay.isAttachedToWindow();
                                }
                                if (continuityActive) {
                                    if (!defer) {
                                        XposedHelpers.setBooleanField(
                                                handle, "mIsRegionDark", requestedDark);
                                    }
                                    hideNativeLauncherHandle(handle, state);
                                    p.setResult(null);
                                }
                            } catch (Throwable t) {
                                verbose("Taskbar color update left native: "
                                        + t.getClass().getSimpleName());
                            }
                        }
                    }).size();
            installed += count;
            log("Launcher color-stability methods=" + count, null);

            count = XposedBridge.hookAllMethods(View.class, "setAlpha",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(p.thisObject instanceof View) || p.args.length == 0
                                    || !(p.args[0] instanceof Number)) return;
                            View handle = (View) p.thisObject;
                            if (!handle.getClass().getName().endsWith("StashedHandleView")) {
                                return;
                            }
                            LauncherHandleAnimationState state;
                            synchronized (LAUNCHER_HANDLE_STATES) {
                                state = LAUNCHER_HANDLE_STATES.get(handle);
                            }
                            if (state == null) return;
                            ContinuityPillView overlay;
                            float requested = ((Number) p.args[0]).floatValue();
                            synchronized (state) {
                                overlay = state.continuityOverlay;
                                if (overlay == null || !overlay.isAttachedToWindow()) return;
                                state.nativeRequestedAlpha = requested;
                            }
                            overlay.setAlpha(requested);
                        }
                    }).size();
            installed += count;
            log("Launcher continuity-alpha methods=" + count, null);

            count = XposedBridge.hookAllMethods(View.class, "setVisibility",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.thisObject instanceof View)) return;
                            View handle = (View) p.thisObject;
                            if (!handle.getClass().getName().endsWith("StashedHandleView")) {
                                return;
                            }
                            LauncherHandleAnimationState state;
                            synchronized (LAUNCHER_HANDLE_STATES) {
                                state = LAUNCHER_HANDLE_STATES.get(handle);
                            }
                            if (state != null) syncContinuityOverlayVisibility(
                                    state.controller, handle, state);
                        }
                    }).size();
            installed += count;
            log("Launcher continuity-visibility methods=" + count, null);
        } else {
            log("Launcher handle class missing", null);
        }
        if (samplingType != null) {
            int count = XposedBridge.hookAllMethods(samplingType, "updateSamplingListener",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object sampled = XposedHelpers.getObjectField(
                                        p.thisObject, "mSampledView");
                                if (!(sampled instanceof View)) return;
                                View handle = (View) sampled;
                                if (!handle.getClass().getName().endsWith("StashedHandleView")) {
                                    return;
                                }
                                if (!isLiveLauncherView(handle)) return;
                                Object baseValue = XposedHelpers.getObjectField(
                                        handle, "mSampledRegion");
                                if (!(baseValue instanceof Rect)) return;
                                Rect base = (Rect) baseValue;
                                if (base.isEmpty()) return;
                                MotionConfig cfg = config(handle.getContext());
                                Rect target = cfg.enabled
                                        ? launcherBackgroundSamplingRegion(handle, base)
                                        : new Rect(base);
                                Object requestValue = XposedHelpers.getObjectField(
                                        p.thisObject, "mSamplingRequestBounds");
                                if (requestValue instanceof Rect
                                        && !target.isEmpty()
                                        && !target.equals(requestValue)) {
                                    ((Rect) requestValue).set(target);
                                    verbose("Taskbar sampling target=" + target
                                            + ", handle=" + base);
                                }
                            } catch (Throwable t) {
                                verbose("Taskbar sampling target unchanged: "
                                        + t.getClass().getSimpleName());
                            }
                        }
                    }).size();
            installed += count;
            log("Launcher sampling target methods=" + count, null);
        } else {
            log("Launcher sampling helper class missing", null);
        }
        installed += hookLauncherHapticHint(loader);
        installed += hookLauncherInputHandler(loader);
        installed += hookLauncherInputConsumer(loader);
        return installed;
    }

    /** Replaces only the initial native hint when a module-owned level is enabled. */
    private static int hookLauncherHapticHint(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(
                "com.android.quickstep.util.ContextualSearchHapticManager", loader);
        if (type == null) return 0;
        int count = XposedBridge.hookAllMethods(type, "vibrateForSearchHint", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!canSkipAnimationMethod(p)) return;
                try {
                    Context context = contextFromObject(p.thisObject);
                    if (context == null) return;
                    MotionConfig cfg = config(context);
                    if (cfg.enabled && cfg.haptics
                            && cfg.hapticStrength != HapticStrength.OFF) {
                        p.setResult(null);
                        verbose("Native search hint replaced by module press haptic");
                    }
                } catch (Throwable t) { log("Launcher search hint left untouched", t); }
            }
        }).size();
        log("Launcher haptic hint methods=" + count, null);
        return count;
    }

    /** Fallback only for the in-process stashed taskbar; remote SystemUiProxy is never invoked. */
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
                    View localHandle = launcherHandleView(p.args[0]);
                    if (localHandle == null) return;
                    Context context = localHandle.getContext();
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
                    View localHandle = launcherHandleView(p.args[0]);
                    if (localHandle == null) return;
                    Context context = localHandle.getContext();
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
                    View localHandle = launcherHandleView(navHandle);
                    if (localHandle == null) return;
                    Context context = localHandle.getContext();
                    MotionConfig cfg = config(context); if (!cfg.enabled) return;
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        if (cfg.longPressOnly || !cfg.animateTouch) return;
                        Object inside = XposedHelpers.callMethod(
                                p.thisObject, "isInNavBarHorizontalArea", event.getRawX());
                        if (inside instanceof Boolean && (Boolean) inside) {
                            cancelDeferredLauncherRelease(launcherStateFor(navHandle, localHandle));
                            driveLauncherNavHandle(navHandle, true, cfg);
                            verbose("Launcher consumer: ACTION_DOWN");
                        }
                    } else if ((action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL)
                            && Boolean.TRUE.equals(LAUNCHER_PRESSED.get(navHandle))) {
                        LauncherHandleAnimationState state = launcherStateFor(
                                navHandle, localHandle);
                        synchronized (state) {
                            state.deferNativeRelease = true;
                            state.releasePending = true;
                            state.awaitingReleaseStateCallback = true;
                        }
                        LAUNCHER_PRESSED.put(navHandle, false);
                        p.setObjectExtra("pixelpill.deferredRelease", navHandle);
                        p.setObjectExtra("pixelpill.releaseOnHome",
                                isLauncherTopTask(p.thisObject, localHandle));
                        verbose("Launcher consumer: ACTION_" + (action == MotionEvent.ACTION_UP
                                ? "UP" : "CANCEL") + " entered system-state barrier");
                    }
                } catch (Throwable t) { log("Launcher input-consumer animation skipped", t); }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object navHandle = p.getObjectExtra("pixelpill.deferredRelease");
                if (navHandle == null) return;
                try {
                    View handle = launcherHandleView(navHandle);
                    if (handle == null) return;
                    LauncherHandleAnimationState state = launcherStateFor(navHandle, handle);
                    synchronized (state) {
                        state.deferNativeRelease = false;
                    }
                    MotionConfig cfg = config(handle.getContext());
                    boolean releaseOnHome = Boolean.TRUE.equals(
                            p.getObjectExtra("pixelpill.releaseOnHome"));
                    if (!maybeStartDeferredLauncherRelease(navHandle, handle, state, cfg,
                            false, releaseOnHome)) {
                        verbose("Taskbar release waiting for stable system state");
                    }
                } catch (Throwable t) {
                    log("Launcher release barrier completion skipped", t);
                }
            }
        }).size();
        log("Launcher input consumer methods=" + count, null);
        return count;
    }

    private static void driveLauncherNavHandle(Object navHandle, boolean down, MotionConfig cfg) {
        View localHandle = launcherHandleView(navHandle);
        if (localHandle == null) return;
        Boolean old = LAUNCHER_PRESSED.get(navHandle); if (old != null && old == down) return;
        invokeLauncherNavHandle(navHandle, down, cfg);
        LAUNCHER_PRESSED.put(navHandle, down);
    }

    private static void invokeLauncherNavHandle(Object navHandle, boolean down, MotionConfig cfg) {
        long duration = Math.max(40, down ? cfg.pressDuration : cfg.releaseDuration);
        XposedHelpers.callMethod(navHandle, "animateNavBarLongPress", down, true, duration);
    }

    private static boolean deferNativeLauncherRelease(Object controller,
            LauncherHandleAnimationState state, XC_MethodHook.MethodHookParam p) {
        synchronized (state) {
            if (!state.deferNativeRelease) return false;
            state.releasePending = true;
        }
        LAUNCHER_PRESSED.put(controller, false);
        if (canSkipAnimationMethod(p)) p.setResult(null);
        return true;
    }

    private static void cancelDeferredLauncherRelease(LauncherHandleAnimationState state) {
        synchronized (state) {
            state.deferNativeRelease = false;
            state.releasePending = false;
            state.awaitingReleaseStateCallback = false;
        }
    }

    /**
     * Start the visual recovery only after Quickstep has finished its ACTION_UP state hand-off.
     * This uses the same state callback that controls the navigation-bar sampling lifecycle; it is
     * not a guessed delay and therefore adapts to slow app/insets transitions.
     */
    private static boolean maybeStartDeferredLauncherRelease(Object controller, View handle,
            LauncherHandleAnimationState state, MotionConfig cfg,
            boolean fromSystemStateCallback, boolean allowWithoutStateCallback) {
        if (!cfg.enabled || !isActiveLauncherHandle(controller, handle)) return false;
        synchronized (state) {
            if (!state.releasePending) return false;
            if (fromSystemStateCallback) state.awaitingReleaseStateCallback = false;
            if (state.deferNativeRelease
                    || (state.awaitingReleaseStateCallback && !allowWithoutStateCallback)) {
                return false;
            }
            if (allowWithoutStateCallback) state.awaitingReleaseStateCallback = false;
        }
        boolean ready;
        try {
            ready = !XposedHelpers.getBooleanField(controller, "mIsAppTransitionPending")
                    && XposedHelpers.getBooleanField(controller, "mIsLumaSamplingEnabled")
                    && XposedHelpers.getBooleanField(controller, "mIsStashed")
                    && !XposedHelpers.getBooleanField(controller, "mTaskbarHidden");
        } catch (Throwable ignored) {
            return false;
        }
        if (!ready) return false;
        synchronized (state) {
            if (!state.releasePending || state.deferNativeRelease) return false;
            state.releasePending = false;
        }
        boolean started = animateLauncherScaleX(controller, handle, 1f,
                cfg.releaseDuration, true, cfg.overshoot);
        if (started) verbose("Taskbar release started after stable system-state callback");
        return started;
    }

    private static boolean isLauncherTopTask(Object inputConsumer, View handle) {
        try {
            Object tracker = XposedHelpers.getObjectField(inputConsumer, "mTopTaskTracker");
            int displayId = handle.getDisplay() == null
                    ? Display.DEFAULT_DISPLAY : handle.getDisplay().getDisplayId();
            Object task = XposedHelpers.callMethod(
                    tracker, "getCachedTopTask", true, displayId);
            Object packageName = XposedHelpers.callMethod(task, "getPackageName");
            return PIXEL_LAUNCHER.equals(packageName) || AOSP_LAUNCHER.equals(packageName);
        } catch (Throwable ignored) {
            return false;
        }
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

    /**
     * Keep Pixel Launcher's native background and outline rendering, but scale only the horizontal
     * axis. The sampled-region hook below compensates for the RenderNode transform so the app-luma
     * target and Circle-to-Search bounds remain stable.
     */
    private static boolean animateLauncherScaleX(Object controller, View handle, float target,
            int duration, boolean returning, float overshoot) {
        LauncherHandleAnimationState state = launcherStateFor(controller, handle);
        boolean continuityReady = ensureContinuityOverlay(controller, handle, state);
        if (!continuityReady && !setTaskbarCachingHint(handle, state, false)) return false;
        ValueAnimator previous;
        float start;
        int token;
        synchronized (state) {
            previous = state.animator;
            state.animator = null;
            state.moduleOwnsDrawing = true;
            if (!returning) {
                state.colorFrozen = true;
                state.postRecoveryColorGuard = false;
                state.baselineRegionDark = launcherRegionDark(handle);
                state.pendingRegionDark = null;
            }
            start = state.currentScale;
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
            float scale = (float) valueAnimator.getAnimatedValue();
            synchronized (state) {
                if (state.animatorToken != token || !state.moduleOwnsDrawing) return;
                state.currentScale = scale;
                handle.setPivotX(handle.getWidth() / 2f);
                handle.setScaleX(state.restingScaleX * scale);
                handle.setScaleY(state.restingScaleY);
                if (state.continuityOverlay != null) {
                    state.continuityOverlay.setPillScale(scale);
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                boolean refreshSample = false;
                synchronized (state) {
                    if (state.animatorToken != token) return;
                    state.animator = null;
                    if (returning && state.colorFrozen) {
                        state.colorFrozen = false;
                        state.postRecoveryColorGuard = true;
                        state.pendingRegionDark = null;
                        refreshSample = true;
                    }
                }
                if (refreshSample) refreshLauncherHandleColor(controller, handle, state);
            }
        });
        synchronized (state) {
            if (state.animatorToken != token) return false;
            state.animator = animator;
        }
        animator.start();
        return true;
    }

    private static LauncherHandleAnimationState launcherStateFor(Object controller, View handle) {
        synchronized (LAUNCHER_HANDLE_STATES) {
            LauncherHandleAnimationState state = LAUNCHER_HANDLE_STATES.get(handle);
            if (state != null && state.controller == controller) return state;
            state = new LauncherHandleAnimationState(controller,
                    safeScale(handle.getScaleX()), safeScale(handle.getScaleY()));
            LauncherHandleAnimationState finalState = state;
            handle.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) {
                    cleanupLauncherHandle(view, finalState.controller);
                }
            });
            LAUNCHER_HANDLE_STATES.put(handle, state);
            return state;
        }
    }

    private static boolean ownsLauncherDrawing(Object controller, View handle) {
        synchronized (LAUNCHER_HANDLE_STATES) {
            LauncherHandleAnimationState state = LAUNCHER_HANDLE_STATES.get(handle);
            return state != null && state.controller == controller && state.moduleOwnsDrawing
                    && handle.isAttachedToWindow();
        }
    }

    private static void cleanupLauncherHandle(View handle, Object controller) {
        LauncherHandleAnimationState state;
        synchronized (LAUNCHER_HANDLE_STATES) {
            state = LAUNCHER_HANDLE_STATES.remove(handle);
        }
        if (state != null) {
            ValueAnimator animator;
            ValueAnimator colorAnimator;
            WindowManager windowManager;
            ContinuityPillView overlay;
            int nativeBackgroundColor;
            boolean restoreNativeBackground;
            synchronized (state) {
                ++state.animatorToken;
                animator = state.animator;
                colorAnimator = state.continuityColorAnimator;
                windowManager = state.continuityWindowManager;
                overlay = state.continuityOverlay;
                nativeBackgroundColor = state.nativeRestoreColor;
                restoreNativeBackground = state.nativeHandleHidden;
                state.animator = null;
                state.continuityColorAnimator = null;
                state.deferNativeRelease = false;
                state.releasePending = false;
                state.awaitingReleaseStateCallback = false;
                state.colorFrozen = false;
                state.postRecoveryColorGuard = false;
                state.baselineRegionDark = null;
                state.pendingRegionDark = null;
                restoreLauncherDrawingLocked(handle, state);
            }
            if (animator != null) animator.cancel();
            if (colorAnimator != null) colorAnimator.cancel();
            if (restoreNativeBackground && handle.getBackground() instanceof ColorDrawable) {
                ((ColorDrawable) handle.getBackground()).setColor(nativeBackgroundColor);
            }
            synchronized (state) {
                state.nativeHandleHidden = false;
                state.continuityWindowManager = null;
                state.continuityOverlay = null;
                state.continuityOverlayParams = null;
                state.continuityOverlayPending = false;
            }
            if (windowManager != null && overlay != null) {
                try { windowManager.removeViewImmediate(overlay); }
                catch (Throwable ignored) {}
            }
            setTaskbarCachingHint(handle, state, true);
        }
        LAUNCHER_PRESSED.remove(controller);
    }

    /**
     * Starts a fresh native sample after the visual recovery is complete. Color callbacks received
     * while the pill is scaling are intentionally discarded: a new app or Circle to Search may
     * replace the navigation Insets leash mid-gesture, so those samples describe a transient
     * composition rather than the final background. No delay is used; the animation's completion
     * callback is the synchronization boundary.
     */
    private static void refreshLauncherHandleColor(Object controller, View handle,
            LauncherHandleAnimationState state) {
        handle.post(() -> {
            try {
                synchronized (state) {
                    if (state.animator != null || state.colorFrozen
                            || state.controller != controller) return;
                }
                if (!isActiveLauncherHandle(controller, handle)) return;
                Object helper = XposedHelpers.getObjectField(controller,
                        "mRegionSamplingHelper");
                if (helper != null) {
                    XposedHelpers.setFloatField(helper, "mLastMedianLuma", -1f);
                }
                XposedHelpers.callMethod(controller, "updateSamplingState");
                verbose("Taskbar color sampling refreshed after scale recovery");
            } catch (Throwable t) {
                verbose("Taskbar color sampling refresh skipped: "
                        + t.getClass().getSimpleName());
            }
        });
    }

    private static Boolean launcherRegionDark(View handle) {
        try {
            Object value = XposedHelpers.getObjectField(handle, "mIsRegionDark");
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Android 17 can hide the task-owned navigation-bar leash for several frames on ACTION_UP.
     * The StashedHandleView remains attached, visible and fully opaque while its pixels disappear,
     * so no View animation can repair that gap. Keep an identical, non-touchable pill in a
     * navigation-bar-panel window: it is owned by Launcher but is not parented to the per-app
     * navigation Insets leash. The native handle stays alive for hit testing and sampling, but is
     * transparent while this continuity surface is attached.
     */
    @SuppressLint("WrongConstant")
    private static boolean ensureContinuityOverlay(Object controller, View handle,
            LauncherHandleAnimationState state) {
        Rect bounds = launcherHandleBounds(controller);
        if (bounds == null || bounds.isEmpty() || handle.getDisplay() == null) return false;
        bounds = launcherHandleScreenBounds(handle, bounds);
        synchronized (state) {
            if (state.continuityOverlay != null
                    && state.continuityOverlay.isAttachedToWindow()) {
                updateContinuityOverlayLayout(handle, bounds, state);
                syncContinuityOverlayVisibility(controller, handle, state);
                hideNativeLauncherHandle(handle, state);
                return true;
            }
            if (state.continuityOverlayPending) return true;
            state.continuityOverlayPending = true;
        }

        try {
            int pillWidth = bounds.width();
            int pillHeight = bounds.height();
            int horizontalPadding = Math.max(32, pillHeight * 4);
            int verticalPadding = Math.max(8, pillHeight);
            int windowWidth = pillWidth + horizontalPadding * 2;
            int windowHeight = pillHeight + verticalPadding * 2;

            Context overlayContext = handle.getContext().createWindowContext(
                    TYPE_NAVIGATION_BAR_PANEL, null);
            WindowManager windowManager = overlayContext.getSystemService(WindowManager.class);
            if (windowManager == null) throw new IllegalStateException("WindowManager unavailable");
            Rect displayAnchor = launcherDisplayAnchor(handle);
            if (displayAnchor.isEmpty()) {
                throw new IllegalStateException("Taskbar display anchor unavailable");
            }
            ContinuityPillView overlay = new ContinuityPillView(
                    overlayContext, pillWidth, pillHeight, launcherBackgroundColor(handle));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    windowWidth, windowHeight, TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.x = bounds.centerX() - displayAnchor.centerX();
            params.y = Math.max(0, displayAnchor.bottom - bounds.bottom - verticalPadding);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0);
            params.setTitle("PixelPill continuity surface");

            synchronized (state) {
                state.continuityWindowManager = windowManager;
                state.continuityOverlay = overlay;
                state.continuityOverlayParams = params;
                state.nativeRequestedAlpha = handle.getAlpha();
            }
            overlay.setVisibility(View.VISIBLE);
            overlay.setAlpha(state.nativeRequestedAlpha);
            windowManager.addView(overlay, params);
            overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    synchronized (state) {
                        state.continuityOverlayPending = false;
                    }
                    syncContinuityOverlayVisibility(controller, handle, state);
                    hideNativeLauncherHandle(handle, state);
                }

                @Override public void onViewDetachedFromWindow(View view) {}
            });
            if (overlay.isAttachedToWindow()) {
                synchronized (state) {
                    state.continuityOverlayPending = false;
                }
                syncContinuityOverlayVisibility(controller, handle, state);
                hideNativeLauncherHandle(handle, state);
            }
            verbose("Taskbar continuity surface attached");
            return true;
        } catch (Throwable t) {
            synchronized (state) {
                state.continuityOverlayPending = false;
                state.continuityWindowManager = null;
                state.continuityOverlay = null;
                state.continuityOverlayParams = null;
            }
            log("Taskbar continuity surface unavailable", t);
            return false;
        }
    }

    private static void updateContinuityOverlayLayout(View handle, Rect bounds,
            LauncherHandleAnimationState state) {
        WindowManager windowManager;
        WindowManager.LayoutParams params;
        ContinuityPillView overlay;
        synchronized (state) {
            windowManager = state.continuityWindowManager;
            params = state.continuityOverlayParams;
            overlay = state.continuityOverlay;
        }
        if (windowManager == null || params == null || overlay == null
                || !overlay.isAttachedToWindow()) return;
        try {
            Rect displayAnchor = launcherDisplayAnchor(handle);
            if (displayAnchor.isEmpty()) return;
            int pillWidth = bounds.width();
            int pillHeight = bounds.height();
            int horizontalPadding = Math.max(32, pillHeight * 4);
            int verticalPadding = Math.max(8, pillHeight);
            int width = pillWidth + horizontalPadding * 2;
            int height = pillHeight + verticalPadding * 2;
            int x = bounds.centerX() - displayAnchor.centerX();
            int y = Math.max(0, displayAnchor.bottom - bounds.bottom - verticalPadding);
            if (params.width == width && params.height == height
                    && params.x == x && params.y == y
                    && overlay.hasPillSize(pillWidth, pillHeight)) return;
            params.width = width;
            params.height = height;
            params.x = x;
            params.y = y;
            overlay.setPillSize(pillWidth, pillHeight);
            windowManager.updateViewLayout(overlay, params);
        } catch (Throwable t) {
            verbose("Taskbar continuity layout update failed: "
                    + t.getClass().getSimpleName());
        }
    }

    private static Rect launcherDisplayAnchor(View handle) {
        try {
            Object windowConfiguration = XposedHelpers.getObjectField(
                    handle.getResources().getConfiguration(), "windowConfiguration");
            Object maxBounds = XposedHelpers.callMethod(windowConfiguration, "getMaxBounds");
            if (maxBounds instanceof Rect && !((Rect) maxBounds).isEmpty()) {
                return new Rect((Rect) maxBounds);
            }
        } catch (Throwable ignored) {}
        android.util.DisplayMetrics metrics = handle.getResources().getDisplayMetrics();
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private static Rect launcherHandleScreenBounds(View handle, Rect localBounds) {
        int[] location = new int[2];
        handle.getLocationOnScreen(location);
        int unscaledX = location[0] - Math.round(handle.getTranslationX())
                + Math.round(handle.getPivotX() * (handle.getScaleX() - 1f));
        int unscaledY = location[1] - Math.round(handle.getTranslationY());
        Rect screenBounds = new Rect(localBounds);
        screenBounds.offset(unscaledX, unscaledY);
        return screenBounds;
    }

    private static void hideNativeLauncherHandle(View handle,
            LauncherHandleAnimationState state) {
        ColorDrawable background;
        synchronized (state) {
            if (state.continuityOverlay == null
                    || !state.continuityOverlay.isAttachedToWindow()) return;
            if (!(handle.getBackground() instanceof ColorDrawable)) return;
            background = (ColorDrawable) handle.getBackground();
            if (!state.nativeHandleHidden) {
                state.nativeRestoreColor = background.getColor();
                state.nativeHandleHidden = true;
            }
        }
        try {
            Object animator = XposedHelpers.getObjectField(handle, "mColorChangeAnim");
            if (animator instanceof Animator) ((Animator) animator).cancel();
            XposedHelpers.setObjectField(handle, "mColorChangeAnim", null);
        } catch (Throwable ignored) {}
        background.setColor(0x00000000);
    }

    private static void syncContinuityOverlayVisibility(Object controller, View handle,
            LauncherHandleAnimationState state) {
        ContinuityPillView overlay;
        float requestedAlpha;
        synchronized (state) {
            overlay = state.continuityOverlay;
            requestedAlpha = state.nativeRequestedAlpha;
        }
        if (overlay == null) return;
        boolean visible = true;
        try {
            visible = XposedHelpers.getBooleanField(controller, "mIsStashed")
                    && !XposedHelpers.getBooleanField(controller, "mTaskbarHidden");
        } catch (Throwable ignored) {}
        overlay.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        overlay.setAlpha(requestedAlpha);
    }

    private static void updateContinuityOverlayColor(View handle,
            LauncherHandleAnimationState state, boolean regionDark, boolean animate) {
        ContinuityPillView overlay;
        ValueAnimator previous;
        int target;
        try {
            target = XposedHelpers.getIntField(handle, regionDark
                    ? "mStashedHandleLightColor" : "mStashedHandleDarkColor");
        } catch (Throwable ignored) {
            return;
        }
        synchronized (state) {
            overlay = state.continuityOverlay;
            previous = state.continuityColorAnimator;
            state.continuityColorAnimator = null;
            state.nativeRestoreColor = target;
        }
        if (overlay == null) return;
        if (previous != null) previous.cancel();
        if (!animate || overlay.getPillColor() == target) {
            overlay.setPillColor(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofArgb(overlay.getPillColor(), target);
        animator.setDuration(120L);
        animator.addUpdateListener(value -> overlay.setPillColor(
                (Integer) value.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                synchronized (state) {
                    if (state.continuityColorAnimator == animation) {
                        state.continuityColorAnimator = null;
                    }
                }
            }
        });
        synchronized (state) {
            state.continuityColorAnimator = animator;
        }
        animator.start();
    }

    private static int launcherBackgroundColor(View handle) {
        return handle.getBackground() instanceof ColorDrawable
                ? ((ColorDrawable) handle.getBackground()).getColor() : 0xebffffff;
    }

    private static void restoreLauncherDrawingLocked(View handle,
            LauncherHandleAnimationState state) {
        if (!state.moduleOwnsDrawing) return;
        state.currentScale = 1f;
        handle.setScaleX(state.restingScaleX);
        handle.setScaleY(state.restingScaleY);
        if (state.continuityOverlay != null) state.continuityOverlay.setPillScale(1f);
        state.moduleOwnsDrawing = false;
    }

    /**
     * Pixel Fold's hardware composer may cache the Taskbar buffer together with the current app.
     * A newly created app/insets leash can then reuse a stale override buffer while the handle is
     * animating. Disable caching only for the active Taskbar ViewRoot surface; no display-wide or
     * application surface policy is changed. Reapply when the active surface identity changes.
     */
    private static boolean setTaskbarCachingHint(View handle,
            LauncherHandleAnimationState state, boolean enabled) {
        Object viewRoot;
        Object surface;
        try {
            viewRoot = XposedHelpers.callMethod(handle, "getViewRootImpl");
            surface = viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
            Object valid = surface == null ? null : XposedHelpers.callMethod(surface, "isValid");
            if (!Boolean.TRUE.equals(valid)) return false;
        } catch (Throwable ignored) {
            return false;
        }
        synchronized (state) {
            if (!enabled && state.cachingDisabled && state.cachingSurface == surface) return true;
            if (enabled && !state.cachingDisabled) return true;
        }
        Object transaction = null;
        try {
            Class<?> transactionType = XposedHelpers.findClass(
                    "android.view.SurfaceControl$Transaction", null);
            transaction = XposedHelpers.newInstance(transactionType);
            XposedHelpers.callMethod(transaction, "setCachingHint", surface, enabled ? 1 : 0);
            XposedHelpers.callMethod(transaction, "apply");
            synchronized (state) {
                state.cachingDisabled = !enabled;
                state.cachingSurface = enabled ? null : surface;
            }
            verbose("Taskbar surface caching " + (enabled ? "restored" : "disabled")
                    + ", surface=0x" + Integer.toHexString(System.identityHashCode(surface)));
            return true;
        } catch (Throwable t) {
            log("Taskbar surface cache policy unchanged", t);
            return false;
        } finally {
            if (transaction != null) {
                try { XposedHelpers.callMethod(transaction, "close"); }
                catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isActiveLauncherHandle(Object controller, View handle) {
        if (controller == null || !isLiveLauncherView(handle)) return false;
        try {
            if (XposedHelpers.getObjectField(controller, "mStashedHandleView") != handle) {
                return false;
            }
            Object viewRoot = XposedHelpers.callMethod(handle, "getViewRootImpl");
            if (viewRoot == null) return false;
            Object surface = XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
            Object valid = surface == null ? null : XposedHelpers.callMethod(surface, "isValid");
            return Boolean.TRUE.equals(valid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLiveLauncherView(View handle) {
        if (handle == null || !handle.isAttachedToWindow() || !handle.isShown()
                || handle.getWindowToken() == null || handle.getDisplay() == null
                || handle.getWidth() <= 0 || handle.getHeight() <= 0
                || handle.getRootView() == null) return false;
        try {
            Object viewRoot = XposedHelpers.callMethod(handle, "getViewRootImpl");
            Object surface = viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
            Object valid = surface == null ? null : XposedHelpers.callMethod(surface, "isValid");
            return Boolean.TRUE.equals(valid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Rect launcherHandleBounds(Object controller) {
        try {
            Object bounds = XposedHelpers.getObjectField(controller, "mStashedHandleBounds");
            return bounds instanceof Rect ? new Rect((Rect) bounds) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void stabilizeLauncherSampledRegion(View handle, Object[] args) {
        LauncherHandleAnimationState state;
        synchronized (LAUNCHER_HANDLE_STATES) {
            state = LAUNCHER_HANDLE_STATES.get(handle);
        }
        if (state == null || !state.moduleOwnsDrawing || args.length == 0
                || !(args[0] instanceof Rect)) return;
        int[] location = new int[2];
        handle.getLocationOnScreen(location);
        int unscaledX = location[0] - Math.round(handle.getTranslationX())
                + Math.round(handle.getPivotX() * (handle.getScaleX() - 1f));
        int unscaledY = location[1] - Math.round(handle.getTranslationY());
        Rect stable = new Rect((Rect) args[0]);
        stable.offset(unscaledX, unscaledY);
        Object sampled = XposedHelpers.getObjectField(handle, "mSampledRegion");
        if (sampled instanceof Rect) ((Rect) sampled).set(stable);
    }

    /**
     * Samples the app above the complete Taskbar surface, not merely above the visible pill.
     * Pixel Fold's handle View is taller than the navigation-bar surface and a newly assigned
     * Insets leash can make the stop-layer exclusion ineffective for a few compositions. A band
     * that overlaps any part of Taskbar can then sample the handle's own color, alternately drive
     * it black and white, and create a self-sustaining release flicker. Keeping the band outside
     * the root surface makes the result independent of both the stop layer and per-task leash.
     * The handle's original mSampledRegion remains unchanged for Circle to Search hit testing.
     */
    private static Rect launcherBackgroundSamplingRegion(View handle, Rect pill) {
        Display display = handle.getDisplay();
        if (display == null) return new Rect(pill);
        android.view.Display.Mode mode = display.getMode();
        int displayWidth = mode.getPhysicalWidth();
        int displayHeight = mode.getPhysicalHeight();
        int rotation = display.getRotation();
        if (rotation == 1 || rotation == 3) {
            int swap = displayWidth;
            displayWidth = displayHeight;
            displayHeight = swap;
        }
        int gap = Math.max(2, pill.height());
        int sampleHeight = Math.max(24, pill.height() * 3);
        int horizontalMargin = pill.width();
        int taskbarTop = pill.top;

        View root = handle.getRootView();
        if (root != null) {
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            if (rootLocation[1] > 0 && rootLocation[1] < taskbarTop) {
                taskbarTop = rootLocation[1];
            }
        }

        // The Fold taskbar ViewRoot may report a logical height larger than its cropped surface.
        // WindowManager's requested height gives the compositor boundary (63 px on this device).
        try {
            Object viewRoot = XposedHelpers.callMethod(handle, "getViewRootImpl");
            Object attributes = viewRoot == null ? null
                    : XposedHelpers.getObjectField(viewRoot, "mWindowAttributes");
            int windowHeight = attributes == null ? 0
                    : XposedHelpers.getIntField(attributes, "height");
            if (windowHeight > 0 && windowHeight < displayHeight) {
                taskbarTop = Math.min(taskbarTop, displayHeight - windowHeight);
            }
        } catch (Throwable ignored) {}

        int sampleBottom = Math.max(sampleHeight, taskbarTop - gap);
        Rect result = new Rect(
                Math.max(0, pill.left - horizontalMargin),
                Math.max(0, sampleBottom - sampleHeight),
                Math.min(displayWidth, pill.right + horizontalMargin),
                Math.min(displayHeight, sampleBottom));
        return result.isEmpty() ? new Rect(pill) : result;
    }

    private static Context contextFromObject(Object object) {
        if (object instanceof View) return ((View) object).getContext();
        for (String field : new String[]{"context", "mContext", "mApplicationContext"}) {
            try {
                Object value = XposedHelpers.getObjectField(object, field);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {}
        }
        return null;
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
     * Starts one compositor-property animation for an ordinary gesture. SystemUI keeps exclusive
     * ownership of drawing, Paint color, dark intensity, and navigation-bar appearance sampling.
     */
    private static boolean startModuleGesture(View handle, MotionConfig cfg,
            boolean nativeCallback) {
        HandleAnimationState state = stateFor(handle);
        int gestureToken;
        float pressedScaleX;
        synchronized (state) {
            gestureToken = state.gestureCycle.beginPress(!nativeCallback);
            if (gestureToken < 0) return false;
            if (!state.moduleOwnsAnimation) {
                state.restingScaleX = safeScale(handle.getScaleX());
            }
            state.moduleOwnsAnimation = true;
            state.activeConfig = cfg;
            pressedScaleX = state.restingScaleX * ratio(cfg);
        }
        animateModuleScale(handle, state, pressedScaleX, cfg.pressDuration, false, cfg.overshoot);
        performModuleHaptic(handle.getContext(), handle, cfg);
        scheduleModuleSafetyRelease(handle, gestureToken);
        return true;
    }

    private static boolean finishModuleGesture(View handle, MotionConfig cfg) {
        HandleAnimationState state = stateFor(handle);
        MotionConfig animationConfig;
        float restingScaleX;
        synchronized (state) {
            if (!state.gestureCycle.beginReturn()) return false;
            animationConfig = state.activeConfig == null ? cfg : state.activeConfig;
            restingScaleX = state.restingScaleX;
        }
        animateModuleScale(handle, state, restingScaleX, animationConfig.releaseDuration, true,
                animationConfig.overshoot);
        return true;
    }

    /**
     * Animates View.scaleX only. Android applies this as a centered RenderNode transform, so the
     * recorded pill is reused instead of forcing NavigationHandle.onDraw on every animation frame.
     */
    private static void animateModuleScale(View handle, HandleAnimationState state, float target,
            int duration, boolean returning, float overshoot) {
        ValueAnimator previous; float start; int token;
        synchronized (state) {
            previous = state.animator;
            state.animator = null;
            start = safeScale(handle.getScaleX());
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
            float animatedScaleX = (float) valueAnimator.getAnimatedValue();
            synchronized (state) {
                if (state.animatorToken != token) return;
                handle.setScaleX(animatedScaleX);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                synchronized (state) {
                    if (state.animatorToken != token) return;
                    state.animator = null;
                    if (returning && !state.gestureCycle.isActive()) {
                        handle.setScaleX(state.restingScaleX);
                        state.moduleOwnsAnimation = false;
                        state.activeConfig = null;
                        state.gestureCycle.markIdle();
                    } else if (!returning && state.gestureCycle.isActive()) {
                        state.gestureCycle.markPressed();
                    }
                }
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
                    if (!state.gestureCycle.isActive(gestureToken)) return;
                }
                finishModuleGesture(handle, config(handle.getContext()));
            } catch (Throwable t) { log("SystemUI module safety release skipped", t); }
        }, 2500L);
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
        primeSettings(context);
        return cached;
    }

    private static boolean ownsSystemUiAnimation(MotionConfig config) {
        return config.enabled && config.animateTouch && !config.longPressOnly;
    }

    private static void primeSettings(Context context) {
        if (context == null) return;
        ensureSettingsReceiver(context);
        if (cacheAt == 0L
                || SystemClock.uptimeMillis() - cacheAt >= CONFIG_REFRESH_INTERVAL_MS) {
            refreshConfigAsync(context);
        }
    }

    /** Never blocks a touch or draw frame on starting the settings app/provider process. */
    private static void refreshConfigAsync(Context context) {
        if (context == null || !CONFIG_REFRESH_IN_FLIGHT.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        Context queryContext = application == null ? context : application;
        Thread refreshThread = new Thread(() -> {
            try {
                Bundle bundle = queryContext.getContentResolver().call(
                        SETTINGS, "get_config", null, null);
                cached = MotionConfig.from(bundle);
            } catch (Throwable t) {
                if (CONFIG_FAILURE_LOG_BUDGET.getAndDecrement() > 0) {
                    log("settings unavailable; non-blocking defaults retained", t);
                }
            } finally {
                cacheAt = SystemClock.uptimeMillis();
                CONFIG_REFRESH_IN_FLIGHT.set(false);
            }
        }, "PixelPillConfigRefresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
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
    private static float safeScale(float value) {
        return Float.isFinite(value) && value > 0.1f ? value : 1f;
    }
    private static boolean isHandle(View v) {
        String name = v.getClass().getName();
        return name.endsWith("NavigationHandle") || name.endsWith("QuickswitchOrientedNavHandle");
    }
    private static Object callOrNull(Object target, String method) {
        try { return XposedHelpers.callMethod(target, method); } catch (Throwable ignored) { return null; }
    }
    private static MotionEvent motionArg(Object[] args) { for (Object a : args) if (a instanceof MotionEvent) return (MotionEvent) a; return null; }
    private static boolean boolArg(Object[] args, int i, boolean fallback) { return args.length > i && args[i] instanceof Boolean ? (Boolean) args[i] : fallback; }
    private static void verbose(String message) {
        if (BuildConfig.VERBOSE_HOOK_LOGS) log(message, null);
    }
    private static void log(String message, Throwable t) {
        if (t == null) XposedBridge.log(TAG + ": " + message);
        else XposedBridge.log(TAG + ": " + message + " (" + t.getClass().getSimpleName() + ")");
    }
    private static final class LauncherHandleAnimationState {
        final Object controller;
        final float restingScaleX;
        final float restingScaleY;
        ValueAnimator animator;
        float currentScale = 1f;
        boolean moduleOwnsDrawing;
        boolean deferNativeRelease;
        boolean releasePending;
        boolean awaitingReleaseStateCallback;
        boolean colorFrozen;
        boolean postRecoveryColorGuard;
        Boolean baselineRegionDark;
        Boolean pendingRegionDark;
        boolean cachingDisabled;
        Object cachingSurface;
        WindowManager continuityWindowManager;
        WindowManager.LayoutParams continuityOverlayParams;
        ContinuityPillView continuityOverlay;
        ValueAnimator continuityColorAnimator;
        float nativeRequestedAlpha = 1f;
        int nativeRestoreColor = 0xebffffff;
        boolean continuityOverlayPending;
        boolean nativeHandleHidden;
        int animatorToken;

        LauncherHandleAnimationState(Object controller, float restingScaleX,
                float restingScaleY) {
            this.controller = controller;
            this.restingScaleX = restingScaleX;
            this.restingScaleY = restingScaleY;
        }
    }

    private static final class ContinuityPillView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pillWidth;
        private float pillHeight;
        private float pillScale = 1f;
        private int pillColor;

        ContinuityPillView(Context context, int pillWidth, int pillHeight, int pillColor) {
            super(context);
            this.pillWidth = pillWidth;
            this.pillHeight = pillHeight;
            this.pillColor = pillColor;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pillColor);
            setWillNotDraw(false);
        }

        int getPillColor() {
            return pillColor;
        }

        void setPillColor(int color) {
            if (pillColor == color) return;
            pillColor = color;
            paint.setColor(color);
            invalidate();
        }

        void setPillScale(float scale) {
            float safe = safeScale(scale);
            if (Math.abs(pillScale - safe) < 0.0001f) return;
            pillScale = safe;
            invalidate();
        }

        boolean hasPillSize(int width, int height) {
            return pillWidth == width && pillHeight == height;
        }

        void setPillSize(int width, int height) {
            if (hasPillSize(width, height)) return;
            pillWidth = width;
            pillHeight = height;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = pillWidth * pillScale;
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - pillHeight) / 2f;
            canvas.drawRoundRect(left, top, left + width, top + pillHeight,
                    pillHeight / 2f, pillHeight / 2f, paint);
        }
    }

    private static final class HandleAnimationState {
        ValueAnimator animator;
        MotionConfig activeConfig;
        final GestureCycle gestureCycle = new GestureCycle();
        float restingScaleX = 1f;
        boolean moduleOwnsAnimation;
        int animatorToken;
    }
}
