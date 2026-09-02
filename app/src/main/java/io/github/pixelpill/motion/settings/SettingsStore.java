package io.github.pixelpill.motion.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

/** Device-protected settings required by SystemUI before the user unlocks or opens this app. */
public final class SettingsStore {
    private SettingsStore() {}

    public static SharedPreferences preferences(Context context) {
        Context deviceContext = context.createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(MotionConfig.PREFS, Context.MODE_PRIVATE);
    }

    /** One-time upgrade from the v1.0.x credential-protected preference location. */
    public static void migrateLegacyPreferences(Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isUserUnlocked()) return;
        Context deviceContext = context.createDeviceProtectedStorageContext();
        SharedPreferences devicePreferences = preferences(context);
        if (!devicePreferences.getAll().isEmpty()) return;
        deviceContext.moveSharedPreferencesFrom(context, MotionConfig.PREFS);
    }
}
