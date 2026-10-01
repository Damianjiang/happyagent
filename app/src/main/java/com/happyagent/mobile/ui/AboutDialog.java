package com.happyagent.mobile.ui;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

// 关于对话框：主菜单"关于"和设置页"版本"共用
public final class AboutDialog {

    private AboutDialog() {}

    public static void show(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Happy Agent");
        b.setMessage("Happy Agent 手机端 · 1.0\n\n"
                + "纯 Java 编写，兼容安卓 6（API 23）及以上。\n"
                + "端侧 agent：可切换多模型供应商（OpenAI / Google / Anthropic），\n"
                + "填对应 API Key 即走真接口，离线也能用本地模拟引擎。\n"
                + "支持多步工具调用、任务暂停/继续/取消、\n"
                + "真实文件 / Shell 沙箱、崩溃自动兜底。\n\n"
                + "图标：黄色微笑圆点");
        b.setPositiveButton("知道了", null);
        b.show();
    }
}
