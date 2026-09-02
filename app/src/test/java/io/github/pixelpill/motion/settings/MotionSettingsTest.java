package io.github.pixelpill.motion.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class MotionSettingsTest {
    @Test public void profilePreferenceValuesAreStableAndUnique() {
        assertEquals(MotionProfile.AOSP_LIKE, MotionProfile.fromPreference("aosp"));
        assertEquals(MotionProfile.PIXEL_SUBTLE, MotionProfile.fromPreference("subtle"));
        assertEquals(MotionProfile.SPRING, MotionProfile.fromPreference("spring"));
        assertEquals(MotionProfile.CUSTOM, MotionProfile.fromPreference("custom"));
        Set<String> values = new HashSet<>();
        for (MotionProfile profile : MotionProfile.values()) {
            assertTrue(values.add(profile.preferenceValue));
        }
    }

    @Test public void presetMotionValuesAreDistinctAndRuntimeSafe() {
        assertNotEquals(MotionProfile.AOSP_LIKE.shrinkRatio,
                MotionProfile.PIXEL_SUBTLE.shrinkRatio, 0f);
        assertNotEquals(MotionProfile.AOSP_LIKE.overshoot,
                MotionProfile.SPRING.overshoot, 0f);
        for (MotionProfile profile : MotionProfile.values()) {
            if (!profile.hasPreset()) continue;
            assertTrue(profile.shrinkRatio >= .5f && profile.shrinkRatio <= .98f);
            assertTrue(profile.pressDuration >= 40);
            assertTrue(profile.releaseDuration >= 40);
            assertTrue(profile.overshoot >= 0f && profile.overshoot <= .35f);
        }
    }

    @Test public void hapticLevelsHaveStableIdsAndIncreasingEffects() {
        assertEquals(HapticStrength.OFF, HapticStrength.fromPreference(0));
        assertEquals(HapticStrength.LIGHT, HapticStrength.fromPreference(1));
        assertEquals(HapticStrength.MEDIUM, HapticStrength.fromPreference(2));
        assertEquals(HapticStrength.STRONG, HapticStrength.fromPreference(3));
        assertTrue(HapticStrength.LIGHT.primitiveScale
                < HapticStrength.MEDIUM.primitiveScale);
        assertTrue(HapticStrength.MEDIUM.primitiveScale
                < HapticStrength.STRONG.primitiveScale);
        assertTrue(HapticStrength.LIGHT.fallbackAmplitude
                < HapticStrength.MEDIUM.fallbackAmplitude);
        assertTrue(HapticStrength.MEDIUM.fallbackAmplitude
                < HapticStrength.STRONG.fallbackAmplitude);
    }
}
