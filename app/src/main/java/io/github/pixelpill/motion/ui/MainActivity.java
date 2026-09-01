package io.github.pixelpill.motion.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import io.github.pixelpill.motion.settings.MotionConfig;

@SuppressLint("SetTextI18n") // The initial public UI is intentionally English-only.
public final class MainActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private PillPreviewView preview;
    private TextView ratioValue, pressValue, releaseValue, overshootValue, modeValue;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        prefs = getSharedPreferences(MotionConfig.PREFS, 0);
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = column();
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("PixelPill Motion"); bar.setSubtitle("Gesture handle, now responsive");
        bar.setPadding(dp(8), dp(6), dp(8), dp(6)); root.addView(bar, match(dp(72)));
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); body.setPadding(dp(16),dp(8),dp(16),dp(32)); scroll.addView(body);

        preview = new PillPreviewView(this); preview.setBackgroundColor(0xff1c1b1f);
        MaterialCardView previewCard = card(); previewCard.addView(preview, match(dp(148))); body.addView(previewCard, match(dp(148)));
        TextView hint = text("Touch the pill to preview · changes apply after System UI restarts", 13); hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(8),0,dp(14)); body.addView(hint);

        MaterialSwitch enabled = toggle("Enable motion", "Master switch for all hook behavior", "enabled", true); body.addView(enabled);
        body.addView(section("Motion profile"));
        modeValue = text("AOSP-like", 16); modeValue.setCompoundDrawablePadding(dp(10));
        MaterialButton mode = button("Animation mode · AOSP-like");
        mode.setOnClickListener(v -> chooseMode(mode)); body.addView(mode, match(dp(52)));
        ratioValue = valueLabel("Press width", percent(getFloat("shrink_ratio",.76f))); body.addView(ratioValue);
        body.addView(slider("shrink_ratio", .55f, .95f, .01f, getFloat("shrink_ratio",.76f), v -> ratioValue.setText("Press width\n"+percent(v))));
        pressValue = valueLabel("Press duration", ms(getInt("press_duration",120))); body.addView(pressValue);
        body.addView(slider("press_duration", 60, 300, 10, getInt("press_duration",120), v -> pressValue.setText("Press duration\n"+ms(Math.round(v)))));
        releaseValue = valueLabel("Release duration", ms(getInt("release_duration",190))); body.addView(releaseValue);
        body.addView(slider("release_duration", 80, 420, 10, getInt("release_duration",190), v -> releaseValue.setText("Release duration\n"+ms(Math.round(v)))));
        overshootValue = valueLabel("Spring overshoot", percent(getFloat("overshoot",.08f))); body.addView(overshootValue);
        body.addView(slider("overshoot", 0, .25f, .01f, getFloat("overshoot",.08f), v -> overshootValue.setText("Spring overshoot\n"+percent(v))));

        body.addView(section("Interaction"));
        body.addView(toggle("Animate ordinary touch", "Respond immediately on ACTION_DOWN", "animate_touch", true));
        body.addView(toggle("Long press only", "Skip normal taps; animate the system long-press chain", "long_press_only", false));
        body.addView(toggle("Haptic feedback", "A restrained tick when the pill is pressed", "haptics", true));
        MaterialButton haptic = button("Haptic strength · Light"); haptic.setOnClickListener(v -> chooseHaptic(haptic)); body.addView(haptic, match(dp(52)));
        body.addView(toggle("Circle to Search compatibility", "Never consume or replace the original long-press gesture", "circle_compatible", true));

        body.addView(section("Tools & information"));
        MaterialButton restart = new MaterialButton(this);
        restart.setText("Restart UI services · Apply now"); restart.setAllCaps(false);
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
        s.addOnChangeListener((slider,v,user)-> { if(!user)return; SharedPreferences.Editor e=prefs.edit(); if(key.contains("duration"))e.putInt(key,Math.round(v));else e.putFloat(key,v); e.apply(); label.set(v); changed(); }); return s;
    }
    private void chooseMode(MaterialButton b) {
        String[] names={"AOSP-like (recommended)","Pixel subtle","Spring","Custom"}; String[] keys={"aosp","subtle","spring","custom"};
        int checked=index(keys,prefs.getString("mode","aosp")); new MaterialAlertDialogBuilder(this).setTitle("Animation mode").setSingleChoiceItems(names,checked,(d,w)->{
            prefs.edit().putString("mode",keys[w]).apply(); b.setText("Animation mode · "+names[w].replace(" (recommended)","")); applyPreset(keys[w]); d.dismiss(); }).show();
    }
    private void applyPreset(String mode) {
        SharedPreferences.Editor e=prefs.edit();
        if(mode.equals("aosp")) e.putFloat("shrink_ratio",.76f).putInt("press_duration",120).putInt("release_duration",190).putFloat("overshoot",.08f);
        if(mode.equals("subtle")) e.putFloat("shrink_ratio",.88f).putInt("press_duration",140).putInt("release_duration",180).putFloat("overshoot",.03f);
        if(mode.equals("spring")) e.putFloat("shrink_ratio",.70f).putInt("press_duration",105).putInt("release_duration",240).putFloat("overshoot",.16f);
        e.apply(); changed(); recreate();
    }
    private void chooseHaptic(MaterialButton b) { String[] n={"Off","Light","Medium","Strong"}; int c=prefs.getInt("haptic_strength",1); new MaterialAlertDialogBuilder(this).setTitle("Haptic strength").setSingleChoiceItems(n,c,(d,w)->{prefs.edit().putInt("haptic_strength",w).putBoolean("haptics",w!=0).apply();b.setText("Haptic strength · "+n[w]);changed();d.dismiss();}).show(); }
    private void showStatus() { new MaterialAlertDialogBuilder(this).setIcon(io.github.pixelpill.motion.R.drawable.ic_launcher).setTitle("Compatibility & hook status").setMessage("Required scope: System UI\nPixel Fold/taskbar scope: Pixel Launcher\nAndroid: "+android.os.Build.VERSION.RELEASE+" (API "+android.os.Build.VERSION.SDK_INT+")\nDevice: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+"\n\nOn Pixel Fold, enable both scopes and reboot once. Framework logs distinguish installed hooks from actual animation calls using tag PixelPillMotion.").setPositiveButton("Done",null).show(); }
    private void showAbout() { new MaterialAlertDialogBuilder(this).setTitle("PixelPill Motion "+BuildConfig.VERSION_NAME).setMessage("A focused, open-source gesture-handle motion module.\n\nMIT licensed. No analytics, network permission, or background service. Settings are exposed read-only to System UI through a minimal provider.").setPositiveButton("Done",null).show(); }
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
                button.setEnabled(true); button.setText("Restart UI services · Apply now");
                if (success) Toast.makeText(this, "UI services restarted — settings applied", Toast.LENGTH_LONG).show();
                else new MaterialAlertDialogBuilder(this).setTitle("Couldn’t restart UI services")
                        .setMessage("Grant PixelPill Motion root access, then try again. You can still apply the module by rebooting the phone." + (reason.isEmpty() ? "" : "\n\nDetails: " + reason))
                        .setPositiveButton("Done", null).show();
            });
        });
    }
    private void resetDefaults() { prefs.edit().clear().apply(); changed(); Toast.makeText(this,"Defaults restored",Toast.LENGTH_SHORT).show(); recreate(); }
    private void changed() { sendBroadcast(new Intent(MotionConfig.ACTION_CHANGED).setPackage("com.android.systemui")); updatePreview(); }
    private void updatePreview() { if(preview!=null) preview.configure(getFloat("shrink_ratio",.76f),getInt("press_duration",120),getInt("release_duration",190),getFloat("overshoot",.08f)); }
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
    private int index(String[] a,String x){for(int i=0;i<a.length;i++)if(a[i].equals(x))return i;return 0;}
    private LinearLayout.LayoutParams match(int h){return new LinearLayout.LayoutParams(-1,h);} private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(52),1);}
    private interface ValueLabel{void set(float v);}
}
