package io.github.pixelpill.motion.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class SettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"get_config".equals(method)) return Bundle.EMPTY;
        SettingsStore.migrateLegacyPreferences(requireContext());
        SharedPreferences p = SettingsStore.preferences(requireContext());
        Bundle b = new Bundle();
        b.putBoolean(MotionConfig.KEY_ENABLED,
                p.getBoolean(MotionConfig.KEY_ENABLED, true));
        b.putString(MotionConfig.KEY_MODE,
                p.getString(MotionConfig.KEY_MODE, MotionProfile.AOSP_LIKE.preferenceValue));
        b.putFloat(MotionConfig.KEY_SHRINK_RATIO,
                p.getFloat(MotionConfig.KEY_SHRINK_RATIO, MotionProfile.AOSP_LIKE.shrinkRatio));
        b.putInt(MotionConfig.KEY_PRESS_DURATION,
                p.getInt(MotionConfig.KEY_PRESS_DURATION, MotionProfile.AOSP_LIKE.pressDuration));
        b.putInt(MotionConfig.KEY_RELEASE_DURATION,
                p.getInt(MotionConfig.KEY_RELEASE_DURATION, MotionProfile.AOSP_LIKE.releaseDuration));
        b.putFloat(MotionConfig.KEY_OVERSHOOT,
                p.getFloat(MotionConfig.KEY_OVERSHOOT, MotionProfile.AOSP_LIKE.overshoot));
        b.putBoolean(MotionConfig.KEY_LONG_PRESS_ONLY,
                p.getBoolean(MotionConfig.KEY_LONG_PRESS_ONLY, false));
        b.putBoolean(MotionConfig.KEY_ANIMATE_TOUCH,
                p.getBoolean(MotionConfig.KEY_ANIMATE_TOUCH, true));
        int strength = p.getInt(MotionConfig.KEY_HAPTIC_STRENGTH,
                HapticStrength.LIGHT.preferenceValue);
        if (!p.getBoolean(MotionConfig.KEY_HAPTICS, true)) {
            strength = HapticStrength.OFF.preferenceValue;
        }
        HapticStrength normalizedStrength = HapticStrength.fromPreference(strength);
        b.putBoolean(MotionConfig.KEY_HAPTICS, normalizedStrength.isEnabled());
        b.putInt(MotionConfig.KEY_HAPTIC_STRENGTH, normalizedStrength.preferenceValue);
        b.putBoolean(MotionConfig.KEY_CIRCLE_COMPATIBLE,
                p.getBoolean(MotionConfig.KEY_CIRCLE_COMPATIBLE, true));
        return b;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String so) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
