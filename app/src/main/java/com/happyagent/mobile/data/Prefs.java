package com.happyagent.mobile.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

// 设置项读写，个人化那块全靠它存
public final class Prefs {

    public static final String KEY_NIGHT_MODE = "night_mode";
    public static final String KEY_START_PAGE = "start_page";
    public static final String KEY_ENABLE_SHAKE_TO_LOG = "enable_shake_to_log";
    public static final String KEY_ENABLE_HAPTICS = "enable_haptics";
    public static final String KEY_WEBUI_ON = "webui_on";
    public static final String KEY_FIRST_LAUNCH = "first_launch";

    // 直接对齐 AppCompat 的常量，别自己造数值
    public static final int NIGHT_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    public static final int NIGHT_NO = AppCompatDelegate.MODE_NIGHT_NO;
    public static final int NIGHT_YES = AppCompatDelegate.MODE_NIGHT_YES;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences("agen_prefs", Context.MODE_PRIVATE);
    }

    public int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public void putInt(String key, int value) {
        sp.edit().putInt(key, value).apply();
    }

    public boolean getBoolean(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBoolean(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }
}
