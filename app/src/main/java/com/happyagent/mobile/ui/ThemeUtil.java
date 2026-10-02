package com.happyagent.mobile.ui;

import android.app.Activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;

// 主题应用：三态（跟随系统/浅色/暗色）× 5 套强调色。
// 每个 Activity 在 setContentView 之前调 apply(activity) 即可。
public final class ThemeUtil {

    private ThemeUtil() {}

    // 启动时全局套夜间模式（Application 用）
    public static void applyGlobal() {
        Prefs p = new Prefs(com.happyagent.mobile.HappyAgentApplication.get());
        AppCompatDelegate.setDefaultNightMode(nightModeOf(p.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW)));
    }

    // 单个 Activity 套「夜间模式 + 强调色 overlay」
    public static void apply(Activity a) {
        if (!(a instanceof AppCompatActivity)) return;
        Prefs p = new Prefs(a.getApplicationContext());

        int accent = p.getInt(Prefs.KEY_ACCENT, Prefs.ACCENT_DEFAULT);
        int resId = R.style.AppTheme;
        switch (accent) {
            case Prefs.ACCENT_TEAL:  resId = R.style.AppTheme_Teal;  break;
            case Prefs.ACCENT_MOSS:  resId = R.style.AppTheme_Moss;   break;
            case Prefs.ACCENT_RUST:  resId = R.style.AppTheme_Rust;   break;
            case Prefs.ACCENT_AMBER: resId = R.style.AppTheme_Amber;  break;
            default: break;
        }
        a.setTheme(resId);
    }

    // 三态转 AppCompat 夜间模式
    public static int nightModeOf(int themeMode) {
        switch (themeMode) {
            case Prefs.THEME_LIGHT: return AppCompatDelegate.MODE_NIGHT_NO;
            case Prefs.THEME_DARK:  return AppCompatDelegate.MODE_NIGHT_YES;
            default:                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    // 三态取 label（设置页分段按钮用）
    public static String modeLabel(int themeMode) {
        switch (themeMode) {
            case Prefs.THEME_LIGHT: return "浅色";
            case Prefs.THEME_DARK:  return "暗色";
            default:                return "跟随系统";
        }
    }

    // 强调色 label（设置页色点用）
    public static String[] accentLabels() {
        return new String[]{"靛蓝", "青绿", "苔绿", "砖红", "琥珀"};
    }
}
