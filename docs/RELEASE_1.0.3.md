# PixelPill Motion v1.0.3

PixelPill Motion v1.0.3 is the stable Pixel Fold outer-display flicker fix. It addresses
the reproducible case where a newly opened application flashed the gesture pill black/white
or made it disappear briefly at ACTION_UP until SystemUI was restarted.

## Verified fault model

The decisive traces did not show a stale or detached Java handle. During the bad release
frames, Pixel Launcher's `StashedHandleView` remained attached and shown with visibility `0`,
alpha `1`, a valid `ViewRoot`, and a valid `VRI-Taskbar` surface. The same View identity and
root could span the gesture. There was also one logical module animator, not two competing
listeners or duplicate animation owners.

WindowManager and SurfaceFlinger evidence instead showed that the outer-display taskbar
navigation window was placed below an application-specific `animation-leash of
insets_animation`. At ACTION_UP that parent leash could suppress the child's composed pixels
for several frames while all Java View properties still reported a healthy handle. Directly
scaling the native handle could not repair a hidden parent composition layer. A native control
run could reproduce the missing frames with PixelPill drawing disabled, confirming that the
drop originated in the Android 17 Fold navigation-surface lifecycle rather than the scale
interpolator.

Opening a new application created a new task/navigation Insets leash, so each new task could
expose the fault again. Restarting SystemUI rebuilt and rebound the navigation surface chain
for tasks that already existed, which explained why those applications became stable while a
later new task could still fail. The unaffected inner-display path did not traverse the same
faulty outer-display taskbar/leash composition sequence.

## Stable architecture

For the Pixel Launcher taskbar path, v1.0.3 creates one small
`TYPE_NAVIGATION_BAR_PANEL` continuity window for the current active handle. It is owned by
Launcher but is not parented to an individual application's transient navigation Insets leash.
The window is non-focusable and non-touchable and draws only an equivalent rounded pill.

The native `StashedHandleView` is deliberately kept attached, shown, and at its requested
alpha. Its `ColorDrawable` is transparent while the continuity window is active, preventing
duplicate pixels without disabling Quickstep's input consumer. This preserves short-press
animation routing, native long-press recognition, haptics, navigation gestures, and Circle to
Search. The continuity pill mirrors the current native position, size, alpha, visibility,
light/dark color, and horizontal animation scale.

Release begins only after Quickstep's real sampling/lifecycle state callback reports a stable
taskbar state. This is callback synchronization, not a guessed delay. The luma sampling band
is moved above the full taskbar surface so the temporary Insets composition cannot cause the
pill to sample its own pixels and oscillate black/white. No app package is special-cased.

## Lifecycle and cleanup

- State is weakly keyed by the current `StashedHandleView`; it is not one global Fold handle.
- Before animation, the controller must still own that handle and its View must be attached,
  shown, sized, tokenized, and backed by a valid `SurfaceControl`.
- Position is recalculated from the current display and screen-space handle bounds, covering
  configuration, rotation, and fold-state changes.
- On detach or module disable, active scale/color animators are cancelled, the continuity
  window is removed, the native background and scale are restored, surface policy is reset,
  and the state entry is discarded.
- Verbose lifecycle diagnostics are opt-in for debug investigations and disabled in stable
  Release builds.

## What was ruled out

- No stale Java `NavigationHandle`, controller, or `ViewRoot` caused the reproduced frame gap.
- No global RenderNode was retained across unrelated handles.
- No duplicate module listener or animator was responsible for the release flash.
- The scale curve remained continuous; the disappearing frame was below the View in the
  surface/leash composition hierarchy.
- The fix does not restart SystemUI, recreate a navbar for each app, delay a gesture by a magic
  timeout, or scan/launch every installed application.

## Physical acceptance

On the connected first-generation Pixel Fold running Android 17, the owner confirmed the
folded outer-display scenario across newly opened applications without another SystemUI
restart: press and release remained smooth and the previous black/white/disappearance flash
did not return. The inner display was not affected by the original bug.

## Build identity

- Package: `io.github.pixelpill.motion`
- Version name: `1.0.3`
- Version code: `10`
- Min SDK: `33`
- Target/compile SDK: `37`
- Channel: stable
- Signing certificate SHA-256:
  `09440B28F2A8C38BEBA2824B236BECFE67823856D0D8199D3ECCE0D5EE4DADE9`

The production signing backup is local-only. It is excluded from Git and release artifacts;
only the public certificate fingerprint may be distributed.
