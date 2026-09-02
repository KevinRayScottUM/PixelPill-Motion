# PixelPill Motion 1.0.2-rc1

This is a local, production-signed release candidate for the remaining gesture-pill
flicker investigation. Do not publish it as a GitHub Release before Pixel Fold testing.

## Confirmed code-level faults and color invariant addressed

1. Quickstep selects either the local stashed taskbar handle or the remote SystemUI
   proxy. The previous fallback invoked both as if they were local, while SystemUI also
   observed the same gesture. Cross-process ordering could therefore start competing
   visual animations.
2. Runtime settings were read through a credential-protected ContentProvider from the
   animation process. Before the user opened PixelPill Motion, provider startup/failure
   could occur on a touch or draw frame.
3. AOSP SystemUI owns the handle Paint color exclusively through `setDarkIntensity`.
   The module previously imposed no gesture-level color invariant, so the revised path
   now guarantees that the color visible at press start cannot alternate during geometry
   compression; it never invents or forces a black/white value.

## New runtime rules

- The normal navigation handle has one visual owner in SystemUI.
- Remote `SystemUiProxy` calls are routed into that same state machine and never invoked
  again by the Launcher fallback.
- Launcher fallback code runs only when a real local `StashedHandleView` is present.
- `onDraw` performs only a centered canvas scale and restore; it does not query settings,
  inspect native animation fields, change Paint, or touch navigation-bar layout/background.
- Dark-intensity changes during compression/return are coalesced and the latest value is
  applied immediately after the return completes.
- Settings are available from device-protected storage and refresh on a short-lived worker,
  never synchronously in a touch or draw callback.
- Existing v1.0.x preferences migrate automatically on the provider's first post-unlock read;
  opening PixelPill Motion is not required for that migration.

## Required cold-start validation

After installing and activating the RC, restart SystemUI once so the new module code is
loaded. Then explicitly test without opening PixelPill Motion again:

1. Reboot the phone and unlock it.
2. Keep PixelPill Motion absent from Recents.
3. Test Pixel Launcher, WeChat, QQ, Settings, and Chrome while folded and unfolded.
4. Repeat across light/dark navigation appearances and portrait/landscape where available.
5. Verify short press, release, cancellation, rapid repeat, press-during-return, Home,
   Back, Recents, app switching, and Circle to Search.

Pass requires no black/white flip, pill disappearance, navbar-background flash, width snap,
or repeated animator restart. Opening PixelPill Motion or using its restart button must not
be required after the initial module-code reload.

## Build identity

- Package: `io.github.pixelpill.motion`
- Version name: `1.0.2-rc1`
- Version code: `8`
- Min SDK: `33`
- Target/compile SDK: `37`
