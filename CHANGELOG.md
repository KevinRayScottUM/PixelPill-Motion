# Changelog

## 1.0.1-rc1 - 2026-09-02

- Fixed Motion Profile display and persistence by using one typed profile value from UI through the cross-process runtime configuration.
- Fixed Haptic Strength persistence and added distinct touch-haptic primitive scales with capability-aware amplitude and view-feedback fallbacks.
- Removed duplicate SystemUI gesture-handle animator triggers and moved ordinary presses to a stable-bounds, draw-only state machine with continuous reversal.
- Added immediate settings-cache invalidation in hooked processes while retaining safe polling fallback behavior.

## 1.0.0 - 2026-09-01

- First public release.
- Responsive gesture-pill press/shrink animation with natural spring-back release motion.
- AOSP-like, Pixel subtle, Spring, and Custom motion profiles.
- Adjustable width, press/release timing, overshoot, touch mode, and haptic feedback.
- Long-press handling designed to preserve the system path used by Circle to Search.
- Dynamic-color Material 3 settings interface with an interactive preview and UI-service refresh action.
- Pixel SystemUI and Pixel Launcher integration for Vector/LSPosed-style Xposed environments.
- Defensive class/method probes and exception isolation for Android SystemUI changes.
- Android 17 / API 37 build target and recorded Pixel Fold development verification.
- Environment-based production signing and reproducible GitHub Actions compile/lint checks.
