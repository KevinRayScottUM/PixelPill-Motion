# PixelPill Motion

PixelPill Motion is a focused Vector / LSPosed module that gives the Pixel gesture-navigation handle a natural press, shrink, and spring-back response while leaving Android's navigation and Circle to Search gesture ownership untouched.

Current public release: **v1.0.1** (`versionCode` 7).

```text
Touch / long-press
        ↓
gesture pill smoothly shrinks
        ↓
long-press continues normally
        ↓
Circle to Search can trigger
        ↓
pill naturally restores and springs back
```

PixelPill Motion changes the handle's visual interaction; it does not replace Android's navigation system or Circle to Search.

## Highlights

- AOSP-like default motion: 76% pressed width, 120 ms press, 190 ms release, 8% overshoot.
- AOSP-like, Pixel subtle, Spring, and Custom profiles.
- Stable-bounds SystemUI drawing that prevents duplicate press animators and third-party-app navbar flashing.
- Immediate touch motion or long-press-only behavior.
- Adjustable width, timing, overshoot, and persisted Off/Light/Medium/Strong haptic levels.
- Material 3 UI with dynamic color, light/dark themes, and an interactive preview.
- Focused scope: `com.android.systemui`, plus Pixel Launcher for the Pixel Fold/taskbar handle.
- Multi-path compatibility probes for Android SystemUI package moves and method signature changes.
- Safe failure: every probe and animation is exception-isolated; no gesture event is consumed or replaced.

## Requirements

- A rooted, compatible Google Pixel device. Pixel / Pixel Fold devices are the intended target family.
- Android 17 / API 37 is the primary design and build target. See the compatibility notes below.
- Vector or LSPosed-compatible framework with legacy Xposed API support.
- Root is normally required by the framework, not by the PixelPill Motion app itself.

## Install and activate

1. Download and install `PixelPill-Motion-v1.0.1-release.apk` from GitHub Releases.
2. Open Vector, LSPosed, or another compatible Xposed manager and enable **PixelPill Motion**.
3. Scope the module to **System UI** (`com.android.systemui`). On the tested Pixel Fold setup, also select **Pixel Launcher** (`com.google.android.apps.nexuslauncher`) because the unfolded/stashed taskbar handle is rendered there.
4. Reboot the phone. For later settings changes, the app's **Restart UI services · Apply now** action can refresh SystemUI and Pixel Launcher after root access is granted; a full reboot remains the safest fallback.
5. Test an ordinary press, then long-press the gesture handle and confirm Circle to Search still starts normally on your installed Pixel build.

If SystemUI behaves unexpectedly, disable the module in Vector/LSPosed and reboot. The app never writes to SystemUI resources or system partitions.

## Compatibility

| Android | Status | Strategy |
|---|---|---|
| 17 / API 37 | Designed and targeted; Pixel Fold press/release motion verified in the recorded Vector development setup | Probes current and moved `NavigationHandle` / `NavigationBarView` class paths, plus the Pixel Launcher taskbar input chain |
| 16 / API 36 | Expected, not comprehensively device-tested | Same AOSP navigation-handle contracts with fallbacks |
| 15 / API 35 | Expected, not comprehensively device-tested | Legacy and moved package names are both probed |
| Other devices / OEM ROMs | Not claimed | OEM SystemUI implementations and private class layouts can differ |

The app has a minimum SDK of 33, but that does not imply verified hook compatibility on every Android release or device. Pixel quarterly updates may rename private SystemUI classes. A failed probe is designed to be a no-op and is logged as `PixelPillMotion`.

## Implementation

For ordinary presses, the module observes `NavigationBarView` touch callbacks and drives one per-handle `IDLE → PRESSING → PRESSED → RETURNING` state machine. The rendered width is changed only inside a `NavigationHandle.onDraw()` Canvas save/restore pair, and an interrupted animation continues from its current rendered scale. Duplicate callbacks and duplicate native `animateLongPress(...)` visual requests are suppressed while the module owns the animation.

The hook does **not** consume `MotionEvent`, replace the SystemUI long-click listener, alter Circle to Search arguments, resize the handle View, or mutate alpha, color, dark intensity, visibility, navbar background, or Region Sampling. It skips only redundant void visual-animation calls; the original SystemUI gesture and long-press pipelines continue normally. The setting provider is read-only and exposes only non-sensitive animation preferences.

Relevant upstream references:

- [AOSP SystemUI NavigationBar](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/navigationbar/NavigationBar.java)
- [AOSP Launcher taskbar handle controller](https://android.googlesource.com/platform/packages/apps/Launcher3/+/master/quickstep/src/com/android/launcher3/taskbar/StashedHandleViewController.java)
- [LSPosed module activation guide](https://github.com/LSPosed/LSPosed/wiki/How-to-use-it)

## Build

Requirements: JDK 17–25 and Android SDK platform 37. The included wrapper uses Gradle 9.1.0.

For a local verification build:

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

For a signed release, provide the signing values outside the repository and run the release tasks:

```powershell
$env:PIXELPILL_RELEASE_STORE_FILE="C:\private\pixelpill-motion-release.jks"
$env:PIXELPILL_RELEASE_STORE_PASSWORD="your-store-password"
$env:PIXELPILL_RELEASE_KEY_ALIAS="pixelpill-motion"
$env:PIXELPILL_RELEASE_KEY_PASSWORD="your-key-password"
./gradlew.bat clean :app:lintRelease :app:assembleRelease
```

Never commit the keystore or passwords. Unsigned release builds remain possible when these variables are absent.

The Xposed API 82 jar is compile-only and is not packaged in the APK. Its SHA-256 is `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`.

## Known limitations

- Private Pixel SystemUI details can change with any OTA; a device log is needed to add a new fallback.
- The app cannot authoritatively query whether a framework injected its hook. Use Vector/LSPosed scope state and the `PixelPillMotion` framework log tag.
- The v1.0.1 animation path was prepared from Pixel Fold reports covering launcher and ordinary-app rendering differences. Recheck press/release motion and Circle to Search after each Pixel OTA.
- Folded/unfolded and transient-navigation states can use different handle instances; each discovered instance is animated independently.

## Privacy and license

No network permission, analytics, account access, or background service. Licensed under MIT. Contributions and device compatibility reports are welcome.
