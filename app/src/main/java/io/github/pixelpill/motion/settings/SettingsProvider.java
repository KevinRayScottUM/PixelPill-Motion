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
        SharedPreferences p = requireContext().getSharedPreferences(MotionConfig.PREFS, 0);
        Bundle b = new Bundle();
        b.putBoolean("enabled", p.getBoolean("enabled", true));
        b.putString("mode", p.getString("mode", "aosp"));
        b.putFloat("shrink_ratio", p.getFloat("shrink_ratio", .76f));
        b.putInt("press_duration", p.getInt("press_duration", 120));
        b.putInt("release_duration", p.getInt("release_duration", 190));
        b.putFloat("overshoot", p.getFloat("overshoot", .08f));
        b.putBoolean("long_press_only", p.getBoolean("long_press_only", false));
        b.putBoolean("animate_touch", p.getBoolean("animate_touch", true));
        b.putBoolean("haptics", p.getBoolean("haptics", true));
        b.putInt("haptic_strength", p.getInt("haptic_strength", 1));
        b.putBoolean("circle_compatible", p.getBoolean("circle_compatible", true));
        return b;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String so) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
