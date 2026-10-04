package com.happyagent.mobile.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AlertDialog;

// 关于对话框：主菜单"关于"和设置页"版本"共用
public final class AboutDialog {

    private AboutDialog() {}

    public static void show(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Happy Agent");
        b.setMessage("Happy Agent 手机端 · v" + versionOf(ctx) + "\n\n"
                + "纯 Java 编写，兼容安卓 6（API 23）及以上。\n"
                + "端侧 agent：可切换多模型供应商（OpenAI / Google / Anthropic），\n"
                + "填对应 API Key 即走真接口，离线也能用本地模拟引擎。\n"
                + "支持多步工具调用、任务暂停/继续/取消、\n"
                + "真实文件 / Shell 沙箱、崩溃自动兜底。");
        b.setNeutralButton("清除全部会话", (d, w) ->
                new AlertDialog.Builder(ctx)
                        .setTitle("清除全部会话？")
                        .setMessage("所有会话与消息将被删除，不可恢复。")
                        .setPositiveButton("清除", (d2, w2) -> {
                            com.happyagent.mobile.data.AgentBackend.get().clearSessions();
                            android.widget.Toast.makeText(ctx, "已清除全部会话",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());
        b.setPositiveButton("知道了", null);
        b.show();
    }

    // 版本号取自包元信息，跟 build.gradle 的 versionName 一致，不写死
    public static String versionOf(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
