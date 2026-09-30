package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

// 设置 / 个性化：主题、强调色、字号、启动页、震动、摇一摇记日志，都存着下次用
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        // 深浅色，切了当场 recreate 套新主题
        SwitchMaterial night = findViewById(R.id.set_night);
        night.setChecked(prefs.getInt(Prefs.KEY_NIGHT_MODE, Prefs.NIGHT_FOLLOW_SYSTEM) == Prefs.NIGHT_YES);
        night.setOnCheckedChangeListener((b, isOn) -> {
            int mode = isOn ? Prefs.NIGHT_YES : Prefs.NIGHT_NO;
            prefs.putInt(Prefs.KEY_NIGHT_MODE, mode);
            AppCompatDelegate.setDefaultNightMode(mode);
            recreate();
        });

        // 强调色，点了存下来，重启后生效
        findViewById(R.id.set_accent_blue).setOnClickListener(v -> setAccent("#1E40AF"));
        findViewById(R.id.set_accent_teal).setOnClickListener(v -> setAccent("#0F766E"));
        findViewById(R.id.set_accent_purple).setOnClickListener(v -> setAccent("#6D28D9"));
        findViewById(R.id.set_accent_rose).setOnClickListener(v -> setAccent("#BE123C"));
        markAccent();

        // 字号缩放，拖动即时套到本页文字上
        SeekBar fontBar = findViewById(R.id.set_font_scale);
        TextView fontLabel = findViewById(R.id.set_font_label);
        float scale = prefs.getFloat(Prefs.KEY_FONT_SCALE, 1.0f);
        fontBar.setMax(100);
        fontBar.setProgress((int) ((scale - 1.0f) / 0.5f * 100));
        fontLabel.setText(String.format(Locale.US, "字号: %.2fx", scale));
        fontBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                float sc = 1.0f + p / 100f * 0.5f;
                prefs.putFloat(Prefs.KEY_FONT_SCALE, sc);
                fontLabel.setText(String.format(Locale.US, "字号: %.2fx", sc));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // 震动反馈
        SwitchMaterial haptics = findViewById(R.id.set_haptics);
        haptics.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_HAPTICS, true));
        haptics.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_HAPTICS, isOn));

        // 摇一摇抓一份日志，方便报问题
        SwitchMaterial shake = findViewById(R.id.set_shake_log);
        shake.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, false));
        shake.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, isOn));

        // 启动停在哪个页：0 会话 / 1 工具
        SwitchMaterial startTools = findViewById(R.id.set_start_tools);
        startTools.setChecked(prefs.getInt(Prefs.KEY_START_PAGE, 0) == 1);
        startTools.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putInt(Prefs.KEY_START_PAGE, isOn ? 1 : 0);
            Toast.makeText(this, "起始页已更新", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.set_back).setOnClickListener(v -> finish());

        // 版本卡点开关于
        findViewById(R.id.set_about_card).setOnClickListener(v -> AboutDialog.show(this));
    }

    private void setAccent(String color) {
        prefs.putString(Prefs.KEY_ACCENT_COLOR, color);
        markAccent();
        Toast.makeText(this, "强调色已更新，重启后生效", Toast.LENGTH_SHORT).show();
    }

    private void markAccent() {
        String cur = prefs.getString(Prefs.KEY_ACCENT_COLOR, "#1E40AF");
        int target = Integer.parseInt(cur.substring(1), 16);
        findViewById(R.id.set_accent_blue).setSelected(target == 0xFF1E40AF);
        findViewById(R.id.set_accent_teal).setSelected(target == 0xFF0F766E);
        findViewById(R.id.set_accent_purple).setSelected(target == 0xFF6D28D9);
        findViewById(R.id.set_accent_rose).setSelected(target == 0xFFBE123C);
    }
}
