package com.happyagent.mobile.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.switchmaterial.SwitchMaterial;

// 外观 · 行为：主题四态（系统/浅色/暗色/灰底）、强调色 5 选 1、会话字号、震动/摇一摇/起始页/朗读
public class AppearanceSettingsActivity extends SectionSettingsActivity {

    private int currentAccent;

    @Override
    protected String title() {
        return "外观 · 行为";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_appearance;
    }

    @Override
    protected void bind() {
        currentAccent = prefs.getInt(Prefs.KEY_ACCENT, Prefs.ACCENT_DEFAULT);
        bindThemeButtons();
        bindAccentRow();
        bindSizeButtons();

        SwitchMaterial haptics = findViewById(R.id.set_haptics);
        haptics.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_HAPTICS, true));
        haptics.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_HAPTICS, isOn));

        SwitchMaterial shake = findViewById(R.id.set_shake_log);
        shake.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, false));
        shake.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, isOn));

        SwitchMaterial startTools = findViewById(R.id.set_start_tools);
        startTools.setChecked(prefs.getInt(Prefs.KEY_START_PAGE, 0) == 1);
        startTools.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putInt(Prefs.KEY_START_PAGE, isOn ? 1 : 0);
            Toast.makeText(this, "起始页已更新", Toast.LENGTH_SHORT).show();
        });

        SwitchMaterial tts = findViewById(R.id.set_tts);
        tts.setChecked(prefs.getBoolean(Prefs.KEY_TTS_ON, false));
        tts.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putBoolean(Prefs.KEY_TTS_ON, isOn);
            if (isOn) com.happyagent.mobile.data.TtsEngine.get().speak("朗读已开启");
            else com.happyagent.mobile.data.TtsEngine.get().stop();
            Toast.makeText(this, isOn ? "朗读已开启" : "朗读已关闭", Toast.LENGTH_SHORT).show();
        });
    }

    private int themeMode() {
        return prefs.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW);
    }

    private void bindThemeButtons() {
        int mode = themeMode();
        segSet(findViewById(R.id.set_theme_follow), mode == Prefs.THEME_FOLLOW);
        segSet(findViewById(R.id.set_theme_light), mode == Prefs.THEME_LIGHT);
        segSet(findViewById(R.id.set_theme_dark), mode == Prefs.THEME_DARK);
        segSet(findViewById(R.id.set_theme_gray), mode == Prefs.THEME_GRAY);
        findViewById(R.id.set_theme_follow).setOnClickListener(v -> setThemeMode(Prefs.THEME_FOLLOW));
        findViewById(R.id.set_theme_light).setOnClickListener(v -> setThemeMode(Prefs.THEME_LIGHT));
        findViewById(R.id.set_theme_dark).setOnClickListener(v -> setThemeMode(Prefs.THEME_DARK));
        findViewById(R.id.set_theme_gray).setOnClickListener(v -> setThemeMode(Prefs.THEME_GRAY));
    }

    private void setThemeMode(int m) {
        if (m == themeMode()) return;
        prefs.putInt(Prefs.KEY_THEME_MODE, m);
        AppCompatDelegate.setDefaultNightMode(ThemeUtil.nightModeOf(m));
        recreate();
    }

    private void bindAccentRow() {
        LinearLayout row = findViewById(R.id.set_accent_row);
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        int size = 30 * d;
        int gap = 14 * d;
        for (int i = 0; i < 5; i++) {
            row.addView(makeAccentDot(i, currentAccent, size, gap));
        }
    }

    private View makeAccentDot(int index, int current, int size, int gap) {
        int d = (int) getResources().getDisplayMetrics().density;
        final LinearLayout dot = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = gap;
        dot.setLayoutParams(lp);
        dot.setGravity(android.view.Gravity.CENTER);

        int solid = resIdOfSolid(index);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (index == current) {
            g.setColor(getThemeColor(resIdOfContainer(index)));
            dot.setBackground(g);
            View inner = new View(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    size - 6 * d, size - 6 * d);
            inner.setLayoutParams(ilp);
            GradientDrawable ig = new GradientDrawable();
            ig.setShape(GradientDrawable.OVAL);
            ig.setColor(getThemeColor(solid));
            inner.setBackground(ig);
            dot.addView(inner);
        } else {
            g.setColor(getThemeColor(solid));
            dot.setBackground(g);
        }
        dot.setOnClickListener(v -> {
            if (currentAccent == index) return;
            currentAccent = index;
            prefs.putInt(Prefs.KEY_ACCENT, index);
            // 整栈重建：背后所有页面也立即换色，不是只变当前页（"切了没反应"的老毛病）
            ThemeUtil.recreateAll();
        });
        return dot;
    }

    private int resIdOfSolid(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber;
            default:                 return R.color.acc_default;
        }
    }

    private int resIdOfContainer(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal_c;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss_c;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust_c;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber_c;
            default:                 return R.color.acc_default_c;
        }
    }

    private void bindSizeButtons() {
        int cur = prefs.getInt(Prefs.KEY_CHAT_TEXT_SIZE, 0);
        segSet(findViewById(R.id.set_size_s), cur == 0);
        segSet(findViewById(R.id.set_size_m), cur == 1);
        segSet(findViewById(R.id.set_size_l), cur == 2);
        findViewById(R.id.set_size_s).setOnClickListener(v -> setChatSize(0));
        findViewById(R.id.set_size_m).setOnClickListener(v -> setChatSize(1));
        findViewById(R.id.set_size_l).setOnClickListener(v -> setChatSize(2));
    }

    private void setChatSize(int v) {
        if (prefs.getInt(Prefs.KEY_CHAT_TEXT_SIZE, 0) == v) return;
        prefs.putInt(Prefs.KEY_CHAT_TEXT_SIZE, v);
        bindSizeButtons();
        Toast.makeText(this, "会话字号已更新", Toast.LENGTH_SHORT).show();
    }

    private void segSet(View v, boolean selected) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        b.setBackgroundResource(selected ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        b.setTextColor(selected ? getThemeColor(resIdOfOn(currentAccent))
                : getThemeColor(R.color.on_surface_variant));
    }

    private int resIdOfOn(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal_c_on;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss_c_on;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust_c_on;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber_c_on;
            default:                 return R.color.acc_default_c_on;
        }
    }
}
