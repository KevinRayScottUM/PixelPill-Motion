# ACTION_UP recovery fix

## Symptom

On the Pixel Fold outer display, the press animation was immediate on Pixel Launcher but could
feel delayed inside an application. More importantly, after `ACTION_UP` the pill could remain at
the configured shrunken width for a long time. Battery saver, an active IME, a newly opened task,
or an application transition made the failure easier to reproduce.

The v1.0.2 continuity surface still prevented the old black/white/disappearing-frame
flicker. The new failure was in the signal used to *start* the return animation, not in the
continuity drawing architecture.

## Root cause

The old Launcher release path marked the gesture as pending and waited for
`StashedHandleViewController.updateSamplingState()`. It then required all of these conditions:

```text
!mIsAppTransitionPending
mIsLumaSamplingEnabled
mIsStashed
!mTaskbarHidden
```

That was an invalid lifecycle dependency. Region/luma sampling is an appearance optimization and
may legitimately be disabled while an application, keyboard, battery state, or navigation
surface is changing. It is not an acknowledgement that Quickstep has completed `ACTION_UP`.
Sometimes no later sampling callback satisfied the full predicate, so the animation remained at
scale `0.76`. Pixel Launcher appeared faster only because the previous implementation gave the
home task a special bypass for the missing callback.

A failing Taobao trace demonstrated the issue directly: `ACTION_UP` arrived with
`lumaEnabled=false`, the pill was still at `scale=0.76`, and recovery was held behind the sampling
barrier. Restarting SystemUI happened to rebuild sampling/navigation state, which explains why it
temporarily cleared the symptom for already-open tasks without fixing the lifecycle error.

## Corrected lifecycle

The Launcher path now uses the touch lifecycle that already owns Circle to Search:

1. `NavHandleLongPressInputConsumer` starts the press animation as soon as it receives
   `ACTION_DOWN`.
2. On `ACTION_UP` or `ACTION_CANCEL`, the module leaves its pressed flag set while Quickstep runs
   the original input-consumer method.
3. Quickstep calls `NavHandleLongPressHandler.onTouchFinished`; the module's after-hook starts the
   return to scale `1.0` immediately after that native completion callback.
4. An after-hook on the input consumer checks the pressed flag and performs the same return only
   when the handler callback was absent. This is a deterministic compatibility fallback, not a
   timeout.

The release no longer reads luma sampling, app-transition, taskbar-hidden, or top-task state.
There is no package allowlist, delayed runnable, installed-app scan, or SystemUI restart.

The continuity overlay remains non-touchable and independent of an application's transient
navigation Insets leash. The native `StashedHandleView` stays attached, visible, and available to
Quickstep for hit testing and long-press recognition.

## Physical verification

Tests ran on the connected first-generation Pixel Fold outer display on Android 17.

| Scenario | `ACTION_DOWN` delivery lag | Release start after `ACTION_UP` | Result |
| --- | ---: | ---: | --- |
| Pixel Launcher | 4 ms | 0 ms | Full-width rest state |
| Chrome | 8 ms | 1 ms | Passed with luma sampling disabled |
| QQ | 7 ms | 1 ms | Passed with luma sampling disabled |
| WeChat | 5 ms | 2 ms | Full-width rest state |
| Settings | 4 ms | 2 ms | Full-width rest state |
| App + IME | — | 2 ms | Full-width rest state |
| Taobao reproduction | 11 ms delivery lag | 3 ms | Passed with luma sampling disabled |

With battery saver active, Chrome, QQ, and Settings each received five rapid tap cycles. All 15
`ACTION_DOWN` events had a matching `ACTION_UP` and immediate recovery, and captured rest frames
showed the pill at full width in every application. The battery simulation and power-save setting
were restored after the test.

A one-second long press launched Google's `ContextualSearchEntrypoint`, and Back returned to the
originating application, confirming that Circle to Search remains on the native Quickstep path.

## Diagnostics

Opt-in diagnostic builds now include the MotionEvent `eventTime`, `downTime`, and calculated input
delivery lag. Stable release builds keep verbose hook logging disabled.
