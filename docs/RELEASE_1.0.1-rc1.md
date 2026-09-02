# PixelPill Motion 1.0.1-rc1

This is a locally signed release candidate for real-device validation. It is not a
public GitHub Release and does not replace the existing v1.0.0 release.

## Candidate focus

- Motion Profile display, persistence, and runtime propagation.
- Haptic Strength persistence, cross-process refresh, and distinct module-owned levels.
- Flicker-resistant SystemUI pill animation across launcher and ordinary app windows.

## Required Pixel Fold validation

Test folded and unfolded, in light and dark navigation appearances where available:

- Pixel Launcher, WeChat, QQ, Settings, and Chrome or another ordinary application.
- AOSP-like, Pixel Subtle, Spring, and Custom profiles.
- Haptics Off, Light, Medium, and Strong.
- Short press/release, long press for Circle to Search, rapid repeat, interrupted return,
  and cancellation.

Pass requires no pill disappearance, alpha/color/background flash, width snap, or
repeated press restart. Home, Back, Recents, app switching, and Circle to Search must
remain functional.

## Build identity

- Package: `io.github.pixelpill.motion`
- Version name: `1.0.1-rc1`
- Version code: `7`
- Min SDK: `33`
- Target/compile SDK: `37`
