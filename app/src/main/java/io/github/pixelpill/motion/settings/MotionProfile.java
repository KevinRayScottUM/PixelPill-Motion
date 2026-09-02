package io.github.pixelpill.motion.settings;

/** Stable persisted motion-profile identifiers shared by the UI and hooked processes. */
public enum MotionProfile {
    AOSP_LIKE("aosp", "AOSP-like", 0.76f, 120, 190, 0.08f),
    PIXEL_SUBTLE("subtle", "Pixel Subtle", 0.88f, 140, 180, 0.03f),
    SPRING("spring", "Spring", 0.70f, 105, 240, 0.16f),
    CUSTOM("custom", "Custom", Float.NaN, 0, 0, Float.NaN);

    public final String preferenceValue;
    public final String displayName;
    public final float shrinkRatio;
    public final int pressDuration;
    public final int releaseDuration;
    public final float overshoot;

    MotionProfile(String preferenceValue, String displayName, float shrinkRatio,
            int pressDuration, int releaseDuration, float overshoot) {
        this.preferenceValue = preferenceValue;
        this.displayName = displayName;
        this.shrinkRatio = shrinkRatio;
        this.pressDuration = pressDuration;
        this.releaseDuration = releaseDuration;
        this.overshoot = overshoot;
    }

    public boolean hasPreset() { return this != CUSTOM; }

    public static MotionProfile fromPreference(String value) {
        for (MotionProfile profile : values()) {
            if (profile.preferenceValue.equals(value)) return profile;
        }
        return AOSP_LIKE;
    }
}
