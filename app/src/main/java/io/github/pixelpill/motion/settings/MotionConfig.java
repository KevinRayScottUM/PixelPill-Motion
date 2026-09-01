package io.github.pixelpill.motion.settings;

import android.os.Bundle;

public final class MotionConfig {
    public static final String AUTHORITY = "io.github.pixelpill.motion.settings";
    public static final String PREFS = "motion_settings";
    public static final String ACTION_CHANGED = "io.github.pixelpill.motion.SETTINGS_CHANGED";

    public boolean enabled = true;
    public String mode = "aosp";
    public float shrinkRatio = 0.76f;
    public int pressDuration = 120;
    public int releaseDuration = 190;
    public float overshoot = 0.08f;
    public boolean longPressOnly = false;
    public boolean animateTouch = true;
    public boolean haptics = true;
    public int hapticStrength = 1;
    public boolean circleCompatible = true;

    public static MotionConfig from(Bundle b) {
        MotionConfig c = new MotionConfig();
        if (b == null) return c;
        c.enabled = b.getBoolean("enabled", c.enabled);
        c.mode = b.getString("mode", c.mode);
        c.shrinkRatio = b.getFloat("shrink_ratio", c.shrinkRatio);
        c.pressDuration = b.getInt("press_duration", c.pressDuration);
        c.releaseDuration = b.getInt("release_duration", c.releaseDuration);
        c.overshoot = b.getFloat("overshoot", c.overshoot);
        c.longPressOnly = b.getBoolean("long_press_only", c.longPressOnly);
        c.animateTouch = b.getBoolean("animate_touch", c.animateTouch);
        c.haptics = b.getBoolean("haptics", c.haptics);
        c.hapticStrength = b.getInt("haptic_strength", c.hapticStrength);
        c.circleCompatible = b.getBoolean("circle_compatible", c.circleCompatible);
        return c;
    }
}
