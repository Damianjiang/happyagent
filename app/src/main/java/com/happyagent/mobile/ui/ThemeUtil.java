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

    // 存活 Activity 弱注册表：换强调色后整栈重建，颜色立即生效（否则只有新页面变色，旧页面要重启才变）
    private static final java.util.Map<Activity, Boolean> sAlive =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Activity, Boolean>());

    // 启动时全局套夜间模式（Application 用）
    public static void applyGlobal() {
        Prefs p = new Prefs(com.happyagent.mobile.HappyAgentApplication.get());
        AppCompatDelegate.setDefaultNightMode(nightModeOf(p.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW)));
    }

    // 单个 Activity 套「夜间模式 + 强调色 overlay」
    public static void apply(Activity a) {
        if (!(a instanceof AppCompatActivity)) return;
        sAlive.put(a, Boolean.TRUE);
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
        // 灰色背景模式：在强调色主题之上叠一层灰底（只改背景/表面色，强调色保留）
        if (p.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW) == Prefs.THEME_GRAY) {
            a.getTheme().applyStyle(R.style.AppTheme_GrayBg, true);
        }
    }

    // 换强调色后调：把还活着的 Activity 全部重建，让新强调色立刻铺满全 App
    public static void recreateAll() {
        java.util.List<Activity> list;
        synchronized (sAlive) {
            list = new java.util.ArrayList<Activity>(sAlive.keySet());
        }
        for (Activity a : list) {
            try {
                if (!a.isFinishing()) a.recreate();
            } catch (Exception ignored) {
            }
        }
    }

    // 主题属性取色：强调色相关一律走主题属性，换色即生效（硬编码 R.color.acc_* 永远是默认色）
    public static int attrColor(android.content.Context c, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (!c.getTheme().resolveAttribute(attr, tv, true)) return 0;
        if (tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(c, tv.resourceId);
        }
        return tv.data;
    }

    // 当前强调色 hex（WebUI 注入主题变量用）；默认色返回 null（页面自带明暗两套默认黄）
    public static String accentHex(android.content.Context c) {
        Prefs p = new Prefs(c.getApplicationContext());
        switch (p.getInt(Prefs.KEY_ACCENT, Prefs.ACCENT_DEFAULT)) {
            case Prefs.ACCENT_TEAL:  return "#2A9D8F";
            case Prefs.ACCENT_MOSS:  return "#5B8C2A";
            case Prefs.ACCENT_RUST:  return "#C44B6C";
            case Prefs.ACCENT_AMBER: return "#B07020";
            default: return null;
        }
    }

    // 主题模式转 AppCompat 夜间模式（灰色固定走浅色底）
    public static int nightModeOf(int themeMode) {
        switch (themeMode) {
            case Prefs.THEME_LIGHT: return AppCompatDelegate.MODE_NIGHT_NO;
            case Prefs.THEME_DARK:  return AppCompatDelegate.MODE_NIGHT_YES;
            case Prefs.THEME_GRAY:  return AppCompatDelegate.MODE_NIGHT_NO;
            default:                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    // 主题模式取 label（设置页分段按钮用）
    public static String modeLabel(int themeMode) {
        switch (themeMode) {
            case Prefs.THEME_LIGHT: return "浅色";
            case Prefs.THEME_DARK:  return "暗色";
            case Prefs.THEME_GRAY:  return "灰色";
            default:                return "跟随系统";
        }
    }

    // 强调色 label（设置页色点用）
    public static String[] accentLabels() {
        return new String[]{"金黄", "青绿", "苔绿", "玫红", "琥珀"};
    }
}
