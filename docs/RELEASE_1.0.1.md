# PixelPill Motion v1.0.1

PixelPill Motion v1.0.1 is a focused stability release for the Pixel gesture-navigation
handle. It fixes persisted settings and removes the duplicate SystemUI animation path
that could make the pill or navigation area flash inside ordinary applications.

## What changed

### Fixed: Motion Profile display and runtime state

Selecting **AOSP-like**, **Pixel Subtle**, **Spring**, or **Custom** now updates the
display immediately and remains correct after reopening the app or rebooting. Preset
parameters and the hooked runtime now read the same canonical profile preference.

**Root cause:** the settings screen rebuilt its mode button with a hard-coded
`AOSP-like` label even when another persisted mode was active.

**Fix locations:**

- `app/src/main/java/io/github/pixelpill/motion/settings/MotionProfile.java`
- `app/src/main/java/io/github/pixelpill/motion/settings/MotionConfig.java`
- `app/src/main/java/io/github/pixelpill/motion/settings/SettingsProvider.java`
- `app/src/main/java/io/github/pixelpill/motion/ui/MainActivity.java`

### Fixed: Haptic Strength persistence and real level differences

**Off**, **Light**, **Medium**, and **Strong** now persist as one typed value and are
propagated immediately to SystemUI and Pixel Launcher. Supported devices use scaled
tick/click haptic primitives; capability-aware amplitude and standard View feedback
remain as fallbacks. Android's touch-feedback setting is respected.

The module emits one press-confirmation haptic for one accepted ACTION_DOWN. It does
not replace or claim control of the later native Circle to Search long-press feedback.

**Root cause:** the UI reconstructed the button as `Light`, while the hook mapped the
stored integer only to semantic `HapticFeedbackConstants`, which did not provide a
reliable intensity scale.

**Fix locations:**

- `app/src/main/java/io/github/pixelpill/motion/settings/HapticStrength.java`
- `app/src/main/java/io/github/pixelpill/motion/settings/MotionConfig.java`
- `app/src/main/java/io/github/pixelpill/motion/settings/SettingsProvider.java`
- `app/src/main/java/io/github/pixelpill/motion/ui/MainActivity.java`
- `app/src/main/java/io/github/pixelpill/motion/xposed/PillMotionHook.java`

### Fixed: Gesture-pill flash/flicker inside third-party apps

Ordinary SystemUI presses now use one deterministic per-handle animation state machine:

```text
IDLE → PRESSING → PRESSED → RETURNING → IDLE
```

Only the pill's rendered width is transformed during `NavigationHandle.onDraw()`.
The outer View bounds, alpha, visibility, color, dark intensity, navigation-bar
background, contrast state, and window insets are not changed. A reversal starts from
the current rendered scale, so a quick release or a new press cannot snap to a preset
endpoint.

**Root cause:** the module observed both `onInterceptTouchEvent` and `onTouchEvent` and
manually called the same native `animateLongPress(...)` visual method that SystemUI's
own long-press chain also invoked. Those overlapping paths could restart or cancel the
native animator during one physical gesture. The investigation found no module code
that resized layout bounds, toggled alpha/visibility, reset handle color/dark intensity,
or changed the navbar background.

**Fix locations:**

- `app/src/main/java/io/github/pixelpill/motion/xposed/PillMotionHook.java`
- `app/src/main/java/io/github/pixelpill/motion/ui/PillPreviewView.java`

## Upgrade and activation

1. Install `PixelPill-Motion-v1.0.1-release.apk`. It uses the same production signing
   identity as v1.0.0 and can be installed as an update.
2. Keep **System UI** (`com.android.systemui`) in the module scope.
3. On Pixel Fold/taskbar configurations, also keep **Pixel Launcher**
   (`com.google.android.apps.nexuslauncher`) in scope.
4. Reboot, or use **Restart UI services · Apply now** where supported.
5. Confirm Home, Back, Recents, app switching, ordinary pill presses, and Circle to
   Search on the installed Pixel build.

## Build identity

- Package: `io.github.pixelpill.motion`
- Version name: `1.0.1`
- Version code: `7`
- Min SDK: `33`
- Target/compile SDK: `37`
- Channel: stable

## Public release assets

- `PixelPill-Motion-v1.0.1-release.apk`
- `PixelPill-Motion-v1.0.1-source.zip`
- `PixelPill-Motion-v1.0.1-SHA256SUMS.txt`
- `PixelPill-Motion-v1.0.1-certificate.txt`
- `PixelPill-Motion-v1.0.1-README.md`

Use the SHA-256 manifest to verify downloaded assets. The certificate file contains
only the public signing identity and fingerprint. No keystore, password, private key,
or signing backup is included in the repository, source archive, or Release assets.
