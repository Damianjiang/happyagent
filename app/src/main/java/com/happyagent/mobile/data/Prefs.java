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
    public static final String KEY_STORAGE_URI = "storage_uri";

    // 个性化：主题三态（0 跟随系统 / 1 浅色 / 2 暗色）
    public static final String KEY_THEME_MODE = "theme_mode";
    // 个性化：强调色下标（0 默认蓝 / 1 青 / 2 苔绿 / 3 砖红 / 4 琥珀）
    public static final String KEY_ACCENT = "accent";
    // 个性化：会话气泡字号（0 标准 / 1 大 / 2 超大）
    public static final String KEY_CHAT_TEXT_SIZE = "chat_text_size";
    // 个性化：角色卡 / 世界书（一段文字设定，注入系统提示词；空=无）
    public static final String KEY_ROLE_CARD = "role_card";
    // 语音：任务完成后自动朗读 AI 回复（TextToSpeech）；麦克风输入是聊天页按钮，不需要开关
    public static final String KEY_TTS_ON = "tts_on";
    // 引擎：ReAct 最大步数（老存档兼容：走 Prefs 不进序列化 State）
    public static final String KEY_MAX_STEPS = "max_steps";

    // 直接对齐 AppCompat 的常量，别自己造数值
    public static final int NIGHT_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    public static final int NIGHT_NO = AppCompatDelegate.MODE_NIGHT_NO;
    public static final int NIGHT_YES = AppCompatDelegate.MODE_NIGHT_YES;

    // 主题三态取值
    public static final int THEME_FOLLOW = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    // 强调色下标
    public static final int ACCENT_DEFAULT = 0, ACCENT_TEAL = 1, ACCENT_MOSS = 2,
            ACCENT_RUST = 3, ACCENT_AMBER = 4;

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

    public boolean contains(String key) {
        return sp.contains(key);
    }

    public boolean getBoolean(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBoolean(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    // 存储授权：存的是 SAF 的 tree uri（授权范围本身由系统管），这里只记它
    public String getStringStorage() {
        return sp.getString(KEY_STORAGE_URI, "");
    }

    public void putStringStorage(String value) {
        sp.edit().putString(KEY_STORAGE_URI, value).apply();
    }

    // 通用字符串存取（角色卡/世界书 JSON 等）
    public String getString(String key, String def) {
        return sp.getString(key, def == null ? "" : def);
    }

    public void putString(String key, String value) {
        sp.edit().putString(key, value == null ? "" : value).apply();
    }
}
