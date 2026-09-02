package io.github.pixelpill.motion.settings;

import android.os.Bundle;

public final class MotionConfig {
    public static final String AUTHORITY = "io.github.pixelpill.motion.settings";
    public static final String PREFS = "motion_settings";
    public static final String ACTION_CHANGED = "io.github.pixelpill.motion.SETTINGS_CHANGED";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MODE = "mode";
    public static final String KEY_SHRINK_RATIO = "shrink_ratio";
    public static final String KEY_PRESS_DURATION = "press_duration";
    public static final String KEY_RELEASE_DURATION = "release_duration";
    public static final String KEY_OVERSHOOT = "overshoot";
    public static final String KEY_LONG_PRESS_ONLY = "long_press_only";
    public static final String KEY_ANIMATE_TOUCH = "animate_touch";
    public static final String KEY_HAPTICS = "haptics"; // Legacy mirror for v1.0.0 migration.
    public static final String KEY_HAPTIC_STRENGTH = "haptic_strength";
    public static final String KEY_CIRCLE_COMPATIBLE = "circle_compatible";

    public boolean enabled = true;
    public MotionProfile profile = MotionProfile.AOSP_LIKE;
    public float shrinkRatio = 0.76f;
    public int pressDuration = 120;
    public int releaseDuration = 190;
    public float overshoot = 0.08f;
    public boolean longPressOnly = false;
    public boolean animateTouch = true;
    public boolean haptics = true;
    public HapticStrength hapticStrength = HapticStrength.LIGHT;
    public boolean circleCompatible = true;

    public static MotionConfig from(Bundle b) {
        MotionConfig c = new MotionConfig();
        if (b == null) return c;
        c.enabled = b.getBoolean(KEY_ENABLED, c.enabled);
        c.profile = MotionProfile.fromPreference(
                b.getString(KEY_MODE, c.profile.preferenceValue));
        c.shrinkRatio = clamp(b.getFloat(KEY_SHRINK_RATIO, c.shrinkRatio), 0.50f, 0.98f);
        c.pressDuration = clamp(b.getInt(KEY_PRESS_DURATION, c.pressDuration), 40, 1000);
        c.releaseDuration = clamp(b.getInt(KEY_RELEASE_DURATION, c.releaseDuration), 40, 1000);
        c.overshoot = clamp(b.getFloat(KEY_OVERSHOOT, c.overshoot), 0f, 0.35f);
        c.longPressOnly = b.getBoolean(KEY_LONG_PRESS_ONLY, c.longPressOnly);
        c.animateTouch = b.getBoolean(KEY_ANIMATE_TOUCH, c.animateTouch);
        c.hapticStrength = HapticStrength.fromPreference(
                b.getInt(KEY_HAPTIC_STRENGTH, c.hapticStrength.preferenceValue));
        c.haptics = c.hapticStrength.isEnabled()
                && b.getBoolean(KEY_HAPTICS, c.hapticStrength.isEnabled());
        if (!c.haptics) c.hapticStrength = HapticStrength.OFF;
        c.circleCompatible = b.getBoolean(KEY_CIRCLE_COMPATIBLE, c.circleCompatible);
        return c;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
