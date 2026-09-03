package io.github.pixelpill.motion.xposed;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Property;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Runtime-only diagnostics enabled for a production-signed diagnostic build. */
final class RuntimeDiagnostics {
    private static final AtomicBoolean SYSTEM_UI_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean LAUNCHER_INSTALLED = new AtomicBoolean();
    private static final AtomicInteger FRAME_LOG_BUDGET = new AtomicInteger(600);
    private static final AtomicInteger MUTATION_LOG_BUDGET = new AtomicInteger(2400);
    private static volatile long DRAW_TRACE_UNTIL;
    private static final Map<View, Boolean> OBSERVED_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RuntimeDiagnostics() {}

    static void hookSystemUi(ClassLoader loader) {
        if (!SYSTEM_UI_INSTALLED.compareAndSet(false, true)) return;
        for (String name : new String[]{
                "com.android.systemui.navigationbar.gestural.NavigationHandle",
                "com.android.systemui.navigationbar.views.NavigationHandle",
                "com.android.systemui.navigationbar.NavigationHandle"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null) continue;
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    observeView("PPM-HANDLE", (View) p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(type, "animateLongPress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    diag("PPM-ANIM", "systemui native " + args(p.args) + " "
                            + viewState((View) p.thisObject));
                }
            });
        }
        diag("PPM-HANDLE", "SystemUI diagnostics installed");
    }

    static void hookLauncher(ClassLoader loader) {
        if (!LAUNCHER_INSTALLED.compareAndSet(false, true)) return;
        Class<?> handleType = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleView", loader);
        Class<?> controllerType = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.StashedHandleViewController", loader);
        Class<?> scalePropertyType = XposedHelpers.findClassIfExists(
                "com.android.launcher3.LauncherAnimUtils$6", loader);
        Class<?> inputType = XposedHelpers.findClassIfExists(
                "com.android.quickstep.inputconsumers.NavHandleLongPressInputConsumer", loader);

        hookHandleViewMutation("setScaleX");
        hookHandleViewMutation("setScaleY");
        hookHandleViewMutation("setAlpha");
        hookHandleViewMutation("setVisibility");
        hookHandleViewMutation("setTransitionAlpha");
        hookHandleViewMutation("setTransitionVisibility");
        hookHandleViewMutation("setClipToOutline");
        hookHandleViewMutation("setPivotX");
        hookHandleViewMutation("setTranslationX");
        hookHandleViewMutation("setTranslationY");
        hookHandleDrawableMutation(ColorDrawable.class, "setAlpha");
        hookHandleDrawableMutation(ColorDrawable.class, "setColor");
        hookHandleDrawableMutation(ColorDrawable.class, "setVisible");

        if (handleType != null) {
            XposedBridge.hookAllConstructors(handleType, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    observeView("PPM-HANDLE", (View) p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(handleType, "updateHandleColor", new XC_MethodHook() {
                private final Map<MethodHookParam, String> before =
                        Collections.synchronizedMap(new WeakHashMap<>());
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    String value = "request=" + args(p.args)
                            + " oldDark=" + field(view, "mIsRegionDark")
                            + " oldAnimator=" + animatorState(fieldObject(view, "mColorChangeAnim"))
                            + " bg=" + backgroundColor(view) + " " + viewState(view);
                    before.put(p, value);
                    diag("PPM-NAVBAR", "color-before " + value);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    diag("PPM-NAVBAR", "color-after prior={" + before.remove(p) + "} newDark="
                            + field(view, "mIsRegionDark") + " animator="
                            + animatorState(fieldObject(view, "mColorChangeAnim"))
                            + " bg=" + backgroundColor(view));
                }
            });
            XposedBridge.hookAllMethods(handleType, "updateSampledRegion", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    diag("PPM-NAVBAR", "sample-region request=" + args(p.args)
                            + " actual=" + field(view, "mSampledRegion") + " " + viewState(view));
                }
            });
        }

        if (controllerType != null) {
            XposedBridge.hookAllConstructors(controllerType, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    diag("PPM-NAVBAR", "controller-created " + controllerState(p.thisObject));
                }
            });
            XposedBridge.hookAllMethods(controllerType, "animateNavBarLongPress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    diag("PPM-ANIM", "launcher-call controller=" + id(p.thisObject)
                            + " args=" + args(p.args) + " " + controllerState(p.thisObject));
                }
            });
            XposedBridge.hookAllMethods(controllerType, "updateSamplingState", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    diag("PPM-NAVBAR", "sampling-before " + controllerState(p.thisObject));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    diag("PPM-NAVBAR", "sampling-after " + controllerState(p.thisObject));
                }
            });
            XposedBridge.hookAllMethods(controllerType, "updateHandleColorOnConnectedDisplay",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            diag("PPM-DISPLAY", "connected-color " + controllerState(p.thisObject));
                        }
                    });
        }

        if (scalePropertyType != null) {
            XposedBridge.hookAllMethods(scalePropertyType, "setValue", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (FRAME_LOG_BUDGET.getAndDecrement() <= 0 || p.args.length < 2
                            || !(p.args[0] instanceof View) || !(p.thisObject instanceof Property)) return;
                    View view = (View) p.args[0];
                    if (!view.getClass().getName().endsWith("StashedHandleView")
                            || !"scale".equals(((Property<?, ?>) p.thisObject).getName())) return;
                    diag("PPM-ANIM", "launcher-frame value=" + p.args[1]
                            + " currentX=" + view.getScaleX() + " currentY=" + view.getScaleY()
                            + " handle=" + id(view) + " root=" + id(view.getRootView()));
                }
            });
        }

        if (inputType != null) {
            XposedBridge.hookAllMethods(inputType, "onMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MotionEvent event = motionEvent(p.args);
                    if (event == null || (event.getActionMasked() != MotionEvent.ACTION_DOWN
                            && event.getActionMasked() != MotionEvent.ACTION_UP
                            && event.getActionMasked() != MotionEvent.ACTION_CANCEL)) return;
                    DRAW_TRACE_UNTIL = SystemClock.elapsedRealtime() + 800L;
                    Object controller = fieldObject(p.thisObject, "mNavHandle");
                    diag("PPM-ANIM", "input consumer=" + id(p.thisObject) + " action="
                            + MotionEvent.actionToString(event.getActionMasked())
                            + " display=" + call(event, "getDisplayId")
                            + " controller=" + id(controller)
                            + " " + controllerState(controller));
                }
            });
        }
        diag("PPM-HANDLE", "Launcher diagnostics installed handle=" + (handleType != null)
                + " controller=" + (controllerType != null));
    }

    private static void hookHandleViewMutation(String methodName) {
        XposedBridge.hookAllMethods(View.class, methodName, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (MUTATION_LOG_BUDGET.get() <= 0 || !(p.thisObject instanceof View)) return;
                View view = (View) p.thisObject;
                if (!view.getClass().getName().endsWith("StashedHandleView")) return;
                MUTATION_LOG_BUDGET.decrementAndGet();
                diag("PPM-MUTATION", methodName + "=" + args(p.args)
                        + " old={scale=" + view.getScaleX() + "," + view.getScaleY()
                        + " alpha=" + view.getAlpha() + " visibility="
                        + view.getVisibility() + "} caller=" + mutationCaller());
            }
        });
    }

    private static void hookHandleDrawableMutation(Class<?> type, String methodName) {
        XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof Drawable)) return;
                View owner = ownerOf((Drawable) p.thisObject);
                if (owner == null) return;
                diag("PPM-DRAWABLE", methodName + "=" + args(p.args)
                        + " oldAlpha=" + ((Drawable) p.thisObject).getAlpha()
                        + " owner=" + viewState(owner));
            }
        });
    }

    private static View ownerOf(Drawable drawable) {
        synchronized (OBSERVED_VIEWS) {
            for (View view : OBSERVED_VIEWS.keySet()) {
                if (view != null && view.getBackground() == drawable) return view;
            }
        }
        return null;
    }

    private static String mutationCaller() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        StringBuilder result = new StringBuilder();
        for (StackTraceElement frame : trace) {
            String name = frame.getClassName();
            if (name.equals(Thread.class.getName())
                    || name.equals(RuntimeDiagnostics.class.getName())
                    || name.startsWith("de.robv.android.xposed.")) continue;
            if (result.length() > 0) result.append(" <- ");
            result.append(name).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (result.toString().split(" <- ").length >= 4) break;
        }
        return result.toString();
    }

    private static void observeView(String tag, View view) {
        if (view == null || OBSERVED_VIEWS.put(view, Boolean.TRUE) != null) return;
        diag(tag, "created " + viewState(view));
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                diag("PPM-ROOT", "attached " + viewState(attached));
            }
            @Override public void onViewDetachedFromWindow(View detached) {
                diag("PPM-ROOT", "detached " + viewState(detached));
            }
        });
        view.addOnLayoutChangeListener((changed, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                diag("PPM-ROOT", "layout old=" + new Rect(oldLeft, oldTop, oldRight, oldBottom)
                        + " new=" + new Rect(left, top, right, bottom) + " " + viewState(changed));
            }
        });
        view.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (SystemClock.elapsedRealtime() <= DRAW_TRACE_UNTIL) {
                diag("PPM-DRAW", "pre-draw " + viewState(view));
            }
            return true;
        });
    }

    private static String controllerState(Object controller) {
        if (controller == null) return "controller=null";
        Object handleObject = fieldObject(controller, "mStashedHandleView");
        View handle = handleObject instanceof View ? (View) handleObject : null;
        Object helper = fieldObject(controller, "mRegionSamplingHelper");
        Object activityRef = fieldObject(controller, "mActivityRef");
        Object activity = activityRef instanceof WeakReference
                ? ((WeakReference<?>) activityRef).get() : null;
        return "controller=" + id(controller)
                + " appPending=" + field(controller, "mIsAppTransitionPending")
                + " stashed=" + field(controller, "mIsStashed")
                + " lumaEnabled=" + field(controller, "mIsLumaSamplingEnabled")
                + " taskbarHidden=" + field(controller, "mTaskbarHidden")
                + " activity=" + id(activity)
                + " primary=" + field(activity, "mIsPrimaryDisplay")
                + " contextDisplay=" + field(activity, "mDisplayId")
                + " handleState={" + viewState(handle) + "} helper={" + helperState(helper, handle) + "}";
    }

    private static String helperState(Object helper, View expectedHandle) {
        if (helper == null) return "null";
        Object sampled = fieldObject(helper, "mSampledView");
        Object stop = fieldObject(helper, "mRegisteredStopLayer");
        Object wrapped = fieldObject(helper, "mWrappedStopLayer");
        return "id=" + id(helper) + " sampled=" + id(sampled)
                + " matchesHandle=" + (sampled == expectedHandle)
                + " enabled=" + field(helper, "mSamplingEnabled")
                + " registered=" + field(helper, "mSamplingListenerRegistered")
                + " visible=" + field(helper, "mWindowVisible")
                + " first=" + field(helper, "mFirstSamplingAfterStart")
                + " waitingDraw=" + field(helper, "mWaitingOnDraw")
                + " request=" + field(helper, "mSamplingRequestBounds")
                + " registeredBounds=" + field(helper, "mRegisteredSamplingBounds")
                + " lastLuma=" + field(helper, "mLastMedianLuma")
                + " currentLuma=" + field(helper, "mCurrentMedianLuma")
                + " stop=" + surfaceState(stop) + " wrapped=" + surfaceState(wrapped);
    }

    private static String viewState(View view) {
        if (view == null) return "view=null";
        Display display = view.getDisplay();
        Context context = view.getContext();
        Configuration configuration = view.getResources().getConfiguration();
        View root = view.getRootView();
        ViewParent parent = view.getParent();
        Object viewRoot = call(view, "getViewRootImpl");
        Object surface = viewRoot == null ? null : call(viewRoot, "getSurfaceControl");
        Object renderNode = fieldObject(view, "mRenderNode");
        Drawable background = view.getBackground();
        return "view=" + id(view) + " class=" + view.getClass().getName()
                + " attached=" + view.isAttachedToWindow() + " shown=" + view.isShown()
                + " visibility=" + view.getVisibility() + "/" + view.getWindowVisibility()
                + " alpha=" + view.getAlpha() + " transitionAlpha="
                + call(view, "getTransitionAlpha")
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " scale=" + view.getScaleX() + "," + view.getScaleY()
                + " clipToOutline=" + view.getClipToOutline()
                + " outlineProvider=" + id(view.getOutlineProvider())
                + " background=" + id(background) + "/"
                + (background == null ? "null"
                        : background.getClass().getName()) + "/" + backgroundColor(view)
                + "/alpha=" + (background == null ? "null" : background.getAlpha())
                + "/visible=" + (background == null ? "null" : background.isVisible())
                + " parent=" + id(parent) + " root=" + id(root)
                + " viewRoot=" + id(viewRoot) + " renderNode=" + id(renderNode)
                + " token=" + id(view.getWindowToken()) + " surface=" + surfaceState(surface)
                + " display={" + displayState(display) + "} contextDisplay="
                + (context == null ? "null" : displayState(context.getDisplay()))
                + " config=" + configuration.screenWidthDp + "x"
                + configuration.screenHeightDp + "@" + configuration.densityDpi
                + "/rot" + (display == null ? "null" : display.getRotation());
    }

    private static String displayState(Display display) {
        if (display == null) return "null";
        return "id=" + display.getDisplayId() + " unique=" + call(display, "getUniqueId")
                + " name=" + display.getName() + " state=" + display.getState();
    }

    private static String surfaceState(Object value) {
        if (value == null) return "null";
        boolean valid = value instanceof SurfaceControl && ((SurfaceControl) value).isValid();
        return id(value) + "/valid=" + valid + "/" + value;
    }

    private static String backgroundColor(View view) {
        if (view == null) return "null";
        Drawable background = view.getBackground();
        return background instanceof ColorDrawable
                ? String.format("#%08x", ((ColorDrawable) background).getColor())
                : id(background);
    }

    private static String animatorState(Object object) {
        if (!(object instanceof Animator)) return id(object);
        Animator animator = (Animator) object;
        return id(animator) + "/started=" + animator.isStarted()
                + "/running=" + animator.isRunning();
    }

    private static MotionEvent motionEvent(Object[] args) {
        if (args == null) return null;
        for (Object value : args) if (value instanceof MotionEvent) return (MotionEvent) value;
        return null;
    }

    private static String args(Object[] args) {
        if (args == null) return "[]";
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) result.append(',');
            result.append(args[i]);
        }
        return result.append(']').toString();
    }

    private static Object fieldObject(Object target, String name) {
        if (target == null) return null;
        try { return XposedHelpers.getObjectField(target, name); }
        catch (Throwable ignored) { return null; }
    }

    private static String field(Object target, String name) {
        if (target == null) return "null";
        try { return String.valueOf(XposedHelpers.getObjectField(target, name)); }
        catch (Throwable ignored) {
            try { return String.valueOf(XposedHelpers.getBooleanField(target, name)); }
            catch (Throwable ignoredAgain) {
                try { return String.valueOf(XposedHelpers.getIntField(target, name)); }
                catch (Throwable ignoredThird) {
                    try { return String.valueOf(XposedHelpers.getFloatField(target, name)); }
                    catch (Throwable finalIgnored) { return "?"; }
                }
            }
        }
    }

    private static Object call(Object target, String method) {
        if (target == null) return null;
        try { return XposedHelpers.callMethod(target, method); }
        catch (Throwable ignored) { return null; }
    }

    private static String id(Object value) {
        return value == null ? "null" : "0x" + Integer.toHexString(System.identityHashCode(value));
    }

    private static void diag(String tag, String message) {
        XposedBridge.log(tag + " " + SystemClock.elapsedRealtime() + " " + message);
    }
}
