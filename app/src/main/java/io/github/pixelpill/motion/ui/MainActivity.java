package io.github.pixelpill.motion.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.appbar.MaterialToolbar;

import io.github.pixelpill.motion.BuildConfig;
import io.github.pixelpill.motion.settings.HapticStrength;
import io.github.pixelpill.motion.settings.MotionConfig;
import io.github.pixelpill.motion.settings.MotionProfile;
import io.github.pixelpill.motion.settings.SettingsStore;

@SuppressLint("SetTextI18n") // The initial public UI is intentionally English-only.
public final class MainActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private PillPreviewView preview;
    private TextView ratioValue, pressValue, releaseValue, overshootValue;
    private MaterialButton modeButton, hapticButton;
    private MaterialSwitch hapticsToggle;
    private Slider ratioSlider, pressSlider, releaseSlider, overshootSlider;
    private boolean syncingHaptics;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        SettingsStore.migrateLegacyPreferences(this);
        prefs = SettingsStore.preferences(this);
        migrateLegacyHapticState();
        setContentView(buildContent());
        notifyRuntimeSettingsChanged();
    }

    private View buildContent() {
        LinearLayout root = column();
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("PixelPill Motion"); bar.setSubtitle("Gesture handle, now responsive");
        bar.setPadding(dp(8), dp(6), dp(8), dp(6)); root.addView(bar, match(dp(72)));
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); body.setPadding(dp(16),dp(8),dp(16),dp(32)); scroll.addView(body);

        preview = new PillPreviewView(this); preview.setBackgroundColor(0xff1c1b1f);
        MaterialCardView previewCard = card(); previewCard.addView(preview, match(dp(148))); body.addView(previewCard, match(dp(148)));
        TextView hint = text("Touch the pill to preview · changes propagate to active hooks", 13); hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(8),0,dp(14)); body.addView(hint);

        MaterialSwitch enabled = toggle("Enable motion", "Master switch for all hook behavior", MotionConfig.KEY_ENABLED, true); body.addView(enabled);
        body.addView(section("Motion profile"));
        MotionProfile currentProfile = currentProfile();
        modeButton = button("Animation mode · " + currentProfile.displayName);
        modeButton.setOnClickListener(v -> chooseMode()); body.addView(modeButton, match(dp(52)));
        ratioValue = valueLabel("Press width", percent(getFloat(MotionConfig.KEY_SHRINK_RATIO,.76f))); body.addView(ratioValue);
        ratioSlider = slider(MotionConfig.KEY_SHRINK_RATIO, .55f, .95f, .01f,
                getFloat(MotionConfig.KEY_SHRINK_RATIO,.76f), v -> ratioValue.setText("Press width\n"+percent(v)));
        body.addView(ratioSlider);
        pressValue = valueLabel("Press duration", ms(getInt(MotionConfig.KEY_PRESS_DURATION,120))); body.addView(pressValue);
        pressSlider = slider(MotionConfig.KEY_PRESS_DURATION, 60, 300, 10,
                getInt(MotionConfig.KEY_PRESS_DURATION,120), v -> pressValue.setText("Press duration\n"+ms(Math.round(v))));
        body.addView(pressSlider);
        releaseValue = valueLabel("Release duration", ms(getInt(MotionConfig.KEY_RELEASE_DURATION,190))); body.addView(releaseValue);
        releaseSlider = slider(MotionConfig.KEY_RELEASE_DURATION, 80, 420, 10,
                getInt(MotionConfig.KEY_RELEASE_DURATION,190), v -> releaseValue.setText("Release duration\n"+ms(Math.round(v))));
        body.addView(releaseSlider);
        overshootValue = valueLabel("Spring overshoot", percent(getFloat(MotionConfig.KEY_OVERSHOOT,.08f))); body.addView(overshootValue);
        overshootSlider = slider(MotionConfig.KEY_OVERSHOOT, 0, .25f, .01f,
                getFloat(MotionConfig.KEY_OVERSHOOT,.08f), v -> overshootValue.setText("Spring overshoot\n"+percent(v)));
        body.addView(overshootSlider);

        body.addView(section("Interaction"));
        body.addView(toggle("Animate ordinary touch", "Respond immediately on ACTION_DOWN", MotionConfig.KEY_ANIMATE_TOUCH, true));
        body.addView(toggle("Long press only", "Skip normal taps; animate the system long-press chain", MotionConfig.KEY_LONG_PRESS_ONLY, false));
        hapticsToggle = buildHapticsToggle(); body.addView(hapticsToggle);
        HapticStrength currentStrength = currentHapticStrength();
        hapticButton = button("Haptic strength · " + currentStrength.displayName);
        hapticButton.setOnClickListener(v -> chooseHaptic()); body.addView(hapticButton, match(dp(52)));
        body.addView(toggle("Circle to Search compatibility", "Never consume or replace the original long-press gesture", MotionConfig.KEY_CIRCLE_COMPATIBLE, true));

        body.addView(section("Tools & information"));
        MaterialButton restart = new MaterialButton(this);
        restart.setText("Restart UI services · After module update"); restart.setAllCaps(false);
        restart.setIconResource(android.R.drawable.ic_popup_sync);
        restart.setOnClickListener(v -> confirmRestartSystemUi(restart));
        body.addView(restart, match(dp(56)));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton play = button("Play preview"); play.setOnClickListener(v -> preview.play()); actions.addView(play, weight());
        MaterialButton reset = button("Restore defaults"); reset.setOnClickListener(v -> resetDefaults()); actions.addView(reset, weight()); body.addView(actions);
        MaterialButton status = button("Compatibility & hook status"); status.setOnClickListener(v -> showStatus()); body.addView(status, match(dp(52)));
        MaterialButton about = button("About PixelPill Motion"); about.setOnClickListener(v -> showAbout()); body.addView(about, match(dp(52)));
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); updatePreview(); return root;
    }

    private MaterialSwitch toggle(String title, String detail, String key, boolean def) {
        MaterialSwitch s = new MaterialSwitch(this); s.setText(title+"\n"+detail); s.setTextSize(15); s.setPadding(dp(4),dp(10),dp(4),dp(10)); s.setChecked(prefs.getBoolean(key,def));
        s.setOnCheckedChangeListener((b,c) -> { prefs.edit().putBoolean(key,c).apply(); changed(); }); return s;
    }
    private Slider slider(String key,float from,float to,float step,float value,ValueLabel label) {
        Slider s=new Slider(this); s.setValueFrom(from); s.setValueTo(to); s.setStepSize(step); s.setValue(value);
        s.addOnChangeListener((slider,v,user)-> {
            if(!user)return;
            SharedPreferences.Editor e=prefs.edit();
            if(MotionConfig.KEY_PRESS_DURATION.equals(key)
                    || MotionConfig.KEY_RELEASE_DURATION.equals(key)) e.putInt(key,Math.round(v));
            else e.putFloat(key,v);
            e.putString(MotionConfig.KEY_MODE, MotionProfile.CUSTOM.preferenceValue).apply();
            modeButton.setText("Animation mode · " + MotionProfile.CUSTOM.displayName);
            label.set(v); changed();
        }); return s;
    }
    private void chooseMode() {
        MotionProfile[] profiles = MotionProfile.values();
        String[] names = new String[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            names[i] = profiles[i].displayName
                    + (profiles[i] == MotionProfile.AOSP_LIKE ? " (recommended)" : "");
        }
        int checked = currentProfile().ordinal();
        new MaterialAlertDialogBuilder(this).setTitle("Animation mode")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    applyProfile(profiles[which]);
                    dialog.dismiss();
                }).show();
    }
    private void applyProfile(MotionProfile profile) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(MotionConfig.KEY_MODE, profile.preferenceValue);
        if (profile.hasPreset()) {
            editor.putFloat(MotionConfig.KEY_SHRINK_RATIO, profile.shrinkRatio)
                    .putInt(MotionConfig.KEY_PRESS_DURATION, profile.pressDuration)
                    .putInt(MotionConfig.KEY_RELEASE_DURATION, profile.releaseDuration)
                    .putFloat(MotionConfig.KEY_OVERSHOOT, profile.overshoot);
        }
        editor.apply();
        modeButton.setText("Animation mode · " + profile.displayName);
        if (profile.hasPreset()) updateMotionControls(profile.shrinkRatio,
                profile.pressDuration, profile.releaseDuration, profile.overshoot);
        changed();
    }
    private void chooseHaptic() {
        HapticStrength[] strengths = HapticStrength.values();
        String[] names = new String[strengths.length];
        for (int i = 0; i < strengths.length; i++) names[i] = strengths[i].displayName;
        new MaterialAlertDialogBuilder(this).setTitle("Haptic strength")
                .setSingleChoiceItems(names, currentHapticStrength().ordinal(), (dialog, which) -> {
                    persistHapticStrength(strengths[which]);
                    dialog.dismiss();
                }).show();
    }
    private void showStatus() {
        MotionProfile profile = currentProfile();
        HapticStrength haptic = currentHapticStrength();
        new MaterialAlertDialogBuilder(this)
                .setIcon(io.github.pixelpill.motion.R.drawable.ic_launcher)
                .setTitle("Compatibility & hook status")
                .setMessage("Current motion profile: " + profile.displayName
                        + "\nCurrent module haptic: " + haptic.displayName
                        + "\nSystemUI animation path: single-owner stable-bounds drawing"
                        + "\nPixel Fold taskbar path: native Launcher scale"
                        + "\nSettings startup path: Direct Boot compatible"
                        + "\n\nRequired scope: System UI"
                        + "\nPixel Fold/taskbar scope: Pixel Launcher"
                        + "\nAndroid: "+android.os.Build.VERSION.RELEASE+" (API "+android.os.Build.VERSION.SDK_INT+")"
                        + "\nDevice: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL
                        + "\n\nThe app cannot directly prove hook injection. Confirm active scopes in the Xposed manager and check the PixelPillMotion framework log after a reboot.")
                .setPositiveButton("Done",null).show();
    }
    private void showAbout() { new MaterialAlertDialogBuilder(this).setTitle("PixelPill Motion "+BuildConfig.VERSION_NAME).setMessage("A focused, open-source gesture-handle motion module.\n\nMIT licensed. No analytics, network permission, or background service. Settings are exposed read-only to System UI through a minimal provider.").setPositiveButton("Done",null).show(); }
    private MaterialSwitch buildHapticsToggle() {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText("Haptic feedback\nA single module-owned press confirmation");
        toggle.setTextSize(15); toggle.setPadding(dp(4),dp(10),dp(4),dp(10));
        toggle.setChecked(currentHapticStrength().isEnabled());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (syncingHaptics) return;
            HapticStrength selected = checked ? HapticStrength.LIGHT : HapticStrength.OFF;
            persistHapticStrength(selected);
        });
        return toggle;
    }
    private void persistHapticStrength(HapticStrength strength) {
        prefs.edit()
                .putInt(MotionConfig.KEY_HAPTIC_STRENGTH, strength.preferenceValue)
                .putBoolean(MotionConfig.KEY_HAPTICS, strength.isEnabled())
                .apply();
        if (hapticButton != null) {
            hapticButton.setText("Haptic strength · " + strength.displayName);
        }
        if (hapticsToggle != null && hapticsToggle.isChecked() != strength.isEnabled()) {
            syncingHaptics = true;
            try {
                hapticsToggle.setChecked(strength.isEnabled());
            } finally {
                syncingHaptics = false;
            }
        }
        changed();
    }
    private void migrateLegacyHapticState() {
        int stored = prefs.getInt(MotionConfig.KEY_HAPTIC_STRENGTH,
                HapticStrength.LIGHT.preferenceValue);
        HapticStrength strength = HapticStrength.fromPreference(stored);
        if (!prefs.getBoolean(MotionConfig.KEY_HAPTICS, true)) strength = HapticStrength.OFF;
        boolean enabled = strength.isEnabled();
        if (stored != strength.preferenceValue
                || prefs.getBoolean(MotionConfig.KEY_HAPTICS, true) != enabled) {
            prefs.edit().putInt(MotionConfig.KEY_HAPTIC_STRENGTH, strength.preferenceValue)
                    .putBoolean(MotionConfig.KEY_HAPTICS, enabled).apply();
        }
    }
    private MotionProfile currentProfile() {
        return MotionProfile.fromPreference(prefs.getString(MotionConfig.KEY_MODE,
                MotionProfile.AOSP_LIKE.preferenceValue));
    }
    private HapticStrength currentHapticStrength() {
        if (!prefs.getBoolean(MotionConfig.KEY_HAPTICS, true)) return HapticStrength.OFF;
        return HapticStrength.fromPreference(prefs.getInt(MotionConfig.KEY_HAPTIC_STRENGTH,
                HapticStrength.LIGHT.preferenceValue));
    }
    private void updateMotionControls(float ratio, int press, int release, float overshoot) {
        ratioSlider.setValue(ratio); pressSlider.setValue(press);
        releaseSlider.setValue(release); overshootSlider.setValue(overshoot);
        ratioValue.setText("Press width\n" + percent(ratio));
        pressValue.setText("Press duration\n" + ms(press));
        releaseValue.setText("Release duration\n" + ms(release));
        overshootValue.setText("Spring overshoot\n" + percent(overshoot));
    }
    private void confirmRestartSystemUi(MaterialButton button) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Restart UI services?")
                .setMessage("The navigation bar and home screen may disappear briefly while Android restarts System UI and Pixel Launcher. Root access is required. Your apps and data will not be closed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", (dialog, which) -> restartSystemUi(button))
                .show();
    }
    private void restartSystemUi(MaterialButton button) {
        button.setEnabled(false); button.setText("Restarting System UI…");
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = false; String detail = "";
            try {
                Process process = new ProcessBuilder("su", "-c", "killall com.android.systemui; killall com.google.android.apps.nexuslauncher").redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line; while ((line = reader.readLine()) != null) output.append(line).append(' ');
                }
                int code = process.waitFor(); ok = code == 0; detail = output.toString().trim();
            } catch (Throwable error) { detail = error.getClass().getSimpleName(); }
            boolean success = ok; String reason = detail;
            runOnUiThread(() -> {
                button.setEnabled(true); button.setText("Restart UI services · After module update");
                if (success) Toast.makeText(this, "UI services restarted — settings applied", Toast.LENGTH_LONG).show();
                else new MaterialAlertDialogBuilder(this).setTitle("Couldn’t restart UI services")
                        .setMessage("Grant PixelPill Motion root access, then try again. You can still apply the module by rebooting the phone." + (reason.isEmpty() ? "" : "\n\nDetails: " + reason))
                        .setPositiveButton("Done", null).show();
            });
        });
    }
    private void resetDefaults() { prefs.edit().clear().apply(); changed(); Toast.makeText(this,"Defaults restored",Toast.LENGTH_SHORT).show(); recreate(); }
    private void changed() {
        notifyRuntimeSettingsChanged();
        updatePreview();
    }
    private void notifyRuntimeSettingsChanged() {
        for (String packageName : new String[]{"com.android.systemui",
                "com.google.android.apps.nexuslauncher", "com.android.launcher3"}) {
            sendBroadcast(new Intent(MotionConfig.ACTION_CHANGED).setPackage(packageName));
        }
    }
    private void updatePreview() { if(preview!=null) preview.configure(
            getFloat(MotionConfig.KEY_SHRINK_RATIO,.76f),
            getInt(MotionConfig.KEY_PRESS_DURATION,120),
            getInt(MotionConfig.KEY_RELEASE_DURATION,190),
            getFloat(MotionConfig.KEY_OVERSHOOT,.08f)); }
    private MaterialCardView card(){ MaterialCardView c=new MaterialCardView(this); c.setRadius(dp(28)); c.setCardElevation(0); return c; }
    private MaterialButton button(String s){ MaterialButton b=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle); b.setText(s); b.setAllCaps(false); return b; }
    private TextView section(String s){ TextView t=text(s,14); t.setTextColor(resolve(androidx.appcompat.R.attr.colorPrimary)); t.setPadding(0,dp(24),0,dp(8)); return t; }
    private TextView valueLabel(String a,String b){ TextView t=text(a+"\n"+b,15); t.setPadding(dp(4),dp(12),dp(4),0); return t; }
    private TextView text(String s,float z){ TextView t=new TextView(this);t.setText(s);t.setTextSize(z);return t; }
    private LinearLayout column(){ LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l; }
    private int resolve(int attr){ android.util.TypedValue v=new android.util.TypedValue();getTheme().resolveAttribute(attr,v,true);return v.data; }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private float getFloat(String k,float d){return prefs.getFloat(k,d);} private int getInt(String k,int d){return prefs.getInt(k,d);}
    private String percent(float f){return Math.round(f*100)+"%";} private String ms(int n){return n+" ms";}
    private LinearLayout.LayoutParams match(int h){return new LinearLayout.LayoutParams(-1,h);} private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(52),1);}
    private interface ValueLabel{void set(float v);}
}
