package com.happyagent.mobile.ui;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.data.Prefs;

// 震动反馈；仅开关开时响。API23 用 vibrate(long)，API26+ 才用 VibrationEffect。
public final class Haptics {

    private Haptics() {}

    // 短促点按反馈
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
