# PixelPill Motion 1.0.2-rc2

This is a local release candidate for Pixel Fold validation. It must not be published
before the reported per-app flicker is verified on the physical device.

## Fault pattern addressed

The remaining behavior is scoped to each newly opened application window: applications
already visible when SystemUI is restarted become stable, while a newly opened application
can still alternate the gesture pill between black and white during compression. That
pattern points to each window's navigation-bar appearance and region-sampling lifecycle,
not to one global settings or gesture-state initialization.

RC1 still intercepted `NavigationHandle.onDraw`, invalidated it on every animation frame,
and deferred `setDarkIntensity` calls during the gesture. Those hooks overlapped the exact
SystemUI paths that resolve a new application's light/dark navigation appearance.

## RC2 rendering contract

- SystemUI has exclusive ownership of `NavigationHandle.onDraw`, its `Paint`,
  `setDarkIntensity`, region sampling, and navigation-bar background.
- PixelPill Motion never caches, suppresses, delays, or replays a black/white color value.
- The pressed width is a centered `View.scaleX` RenderNode property transform. It does not
  change layout bounds or require the handle to be redrawn for every animation frame.
- A gesture starts from the handle's current valid scale and returns to the exact scale that
  existed before the gesture, including interrupted-return and rapid-repeat paths.
- RC1's single visual owner, Direct Boot configuration cache, haptic de-duplication, and
  Pixel Fold stashed-handle routing remain in place.

## Required device validation

After installing and activating RC2, restart SystemUI once only to load the new module code.
Do not restart it again during the following test:

1. Open at least six applications that were not already open, one at a time.
2. In every newly opened application, immediately press and release the gesture pill.
3. Repeat with applications using light, dark, transparent, and contrasting navigation bars.
4. Switch repeatedly among all opened applications and retest the pill.
5. Repeat folded and unfolded, then portrait and landscape where supported.
6. Verify rapid repeat, cancellation, press during return, Home, Back, Recents, app switching,
   haptics, and Circle to Search.

Pass requires stable color and smooth width animation in every new application without any
additional SystemUI restart. There must be no black/white flip, disappearance, width snap,
background flash, duplicated haptic, or regression in system navigation.

## Build identity

- Package: `io.github.pixelpill.motion`
- Version name: `1.0.2-rc2`
- Version code: `9`
- Min SDK: `33`
- Target/compile SDK: `37`
