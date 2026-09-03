# Changelog

## 1.0.3 - 2026-09-03

- Fixed the Android 17 Pixel Fold outer-display pill flash that returned for every newly opened application until SystemUI was restarted.
- Moved the Fold taskbar pill pixels to a small Launcher-owned navigation-panel continuity surface that is independent of each application's transient navigation Insets animation leash.
- Kept the native `StashedHandleView` attached, visible, and fully available to Quickstep for touch handling, long-press recognition, region state, and Circle to Search; only its duplicate drawable pixels are transparent while the continuity surface is active.
- Bound animation state to the current attached handle and valid `ViewRoot`/`SurfaceControl`, with detach cleanup that cancels animators, removes the continuity window, restores native drawing, and discards stale state.
- Synchronized release with Quickstep's real sampling/lifecycle callback and moved luma sampling above the complete taskbar surface to prevent self-sampling black/white feedback. No app-specific rules, guessed delay, or automatic SystemUI restart is used.
- Added opt-in lifecycle, handle, surface, animation, and navigation-color diagnostics for future Pixel OTA investigations; stable builds keep verbose diagnostics disabled.

## 1.0.2-rc2 - 2026-09-02

- Removed all SystemUI `NavigationHandle.onDraw` interception and per-frame Canvas invalidation.
- Removed dark-intensity deferral so each newly opened app's navigation-bar appearance and region sampling remain entirely owned by SystemUI.
- Moved the press/release effect to a centered `View.scaleX` RenderNode transform that preserves layout bounds and reuses SystemUI's recorded pill content.
- Preserved single-owner gesture arbitration, non-blocking Direct Boot settings, haptic de-duplication, and Pixel Fold stashed-handle handling from RC1.

## 1.0.2-rc1 - 2026-09-02

- Removed the cross-process race between Quickstep's remote `SystemUiProxy` animation and the module's SystemUI touch observer by assigning the main gesture handle one SystemUI animation owner.
- Limited Launcher fallbacks to the actual in-process Pixel Fold stashed/taskbar handle and de-duplicated its native callbacks.
- Moved settings to Direct Boot-compatible device-protected storage and made runtime refresh asynchronous so a stopped settings app or unavailable provider cannot block a touch/draw frame.
- Stabilized the current SystemUI handle color for the duration of a pill gesture, then applied only the latest deferred dark-intensity value on return.
- Added deterministic gesture-cycle tests for duplicate callbacks and interrupted return animations.

## 1.0.1 - 2026-09-02

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
