# PixelPill Motion v1.0.0

PixelPill Motion's first public release.

## Highlights

- Responsive gesture-pill press and shrink animation.
- Natural return and spring animation.
- Long-press interaction designed to preserve the system path used by Circle to Search.
- AOSP-like, Pixel subtle, Spring, and fully customizable motion profiles.
- Width, timing, overshoot, touch-mode, and haptic feedback controls.
- Polished Material 3 configuration interface with dynamic color and an interactive preview.
- Pixel/SystemUI-oriented integration for Vector/LSPosed-style Xposed environments.
- Android 17 / API 37 target.

## Installation

1. Install `PixelPill-Motion-v1.0.0-release.apk` on a rooted, compatible Pixel device.
2. Enable PixelPill Motion in Vector, LSPosed, or another compatible Xposed manager.
3. Scope it to `com.android.systemui`. On the tested Pixel Fold setup, also scope `com.google.android.apps.nexuslauncher`.
4. Reboot once, or use **Restart UI services · Apply now** for later settings changes where supported.
5. Test an ordinary press and confirm Circle to Search still starts normally on long-press.

## Compatibility

- Designed and built for Android 17 / API 37 and Google Pixel SystemUI.
- Developed for Vector/LSPosed-style Xposed environments.
- Press/release motion was verified in the recorded Pixel Fold / Android 17 / Vector development setup.
- Other Pixel generations, Android builds, and OEM SystemUI implementations may differ and are not universally claimed as tested.

PixelPill Motion changes the visual response of the gesture handle. It does not replace Android navigation or Circle to Search, consume the navigation event, or replace the system long-press listener.

## Verification

- `versionName`: `1.0.0`
- `versionCode`: `6`
- Release channel: stable, non-debuggable build
- Production signing identity: verified against the prior internal production certificate
- Android release build and lint: passed
- Packaged Xposed entry and scopes: verified

See `PixelPill-Motion-v1.0.0-SHA256SUMS.txt` in the release assets for the newly generated artifact checksums.
