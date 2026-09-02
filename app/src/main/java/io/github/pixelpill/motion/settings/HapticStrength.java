package io.github.pixelpill.motion.settings;

/** Stable persisted module-owned press-haptic levels. */
public enum HapticStrength {
    OFF(0, "Off", 0f, 0, 0),
    LIGHT(1, "Light", 0.35f, 8, 70),
    MEDIUM(2, "Medium", 0.65f, 12, 150),
    STRONG(3, "Strong", 1f, 16, 230);

    public final int preferenceValue;
    public final String displayName;
    public final float primitiveScale;
    public final int fallbackDurationMs;
    public final int fallbackAmplitude;

    HapticStrength(int preferenceValue, String displayName, float primitiveScale,
            int fallbackDurationMs, int fallbackAmplitude) {
        this.preferenceValue = preferenceValue;
        this.displayName = displayName;
        this.primitiveScale = primitiveScale;
        this.fallbackDurationMs = fallbackDurationMs;
        this.fallbackAmplitude = fallbackAmplitude;
    }

    public boolean isEnabled() { return this != OFF; }

    public static HapticStrength fromPreference(int value) {
        for (HapticStrength strength : values()) {
            if (strength.preferenceValue == value) return strength;
        }
        return LIGHT;
    }
}
