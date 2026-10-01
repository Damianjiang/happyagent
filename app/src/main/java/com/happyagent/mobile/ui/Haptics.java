package com.happyagent.mobile.ui;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.data.Prefs;

// 震动反馈：只在「行为>震动」开关开时才响。
// API23：Vibrator.vibrate(long) 全支持，VibrationEffect 仅 API26+ 才用（版本分支）。
public final class Haptics {

    private Haptics() {}

    // 点按钮等短促反馈。开关关闭则直接不响
    public static void tap(View view) {
        if (!enabled()) return;
        Context ctx = HappyAgentApplication.get();
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(18);
        }
    }

    // 发送/完成等稍强反馈
    public static void action() {
        if (!enabled()) return;
        Context ctx = HappyAgentApplication.get();
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(40);
        }
    }

    private static boolean enabled() {
        return new Prefs(HappyAgentApplication.get())
                .getBoolean(Prefs.KEY_ENABLE_HAPTICS, true);
    }
}
