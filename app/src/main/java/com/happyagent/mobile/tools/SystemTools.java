package com.happyagent.mobile.tools;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 设备状态查询：设备 / 电池 / 存储 / 网络 / 剪贴板，只读。
public final class SystemTools {

    private final Context ctx;

    public SystemTools(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // 设备概要：厂商/型号/API、可用内存、内部存储、网络、当前时间
    public String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("厂商: ").append(Build.MANUFACTURER)
          .append(" / 型号: ").append(Build.MODEL)
          .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("\nRAM 总量 ").append(mb(Runtime.getRuntime().totalMemory()))
          .append("，可用 ").append(mb(Runtime.getRuntime().freeMemory())).append('\n');
        sb.append(storageInfo());
        sb.append('\n').append(networkInfo());
        sb.append("\n本地时间 ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss E", Locale.CHINA).format(new Date()));
        return sb.toString();
    }

    public String storageInfo() {
        StatFs fs = new StatFs(ctx.getFilesDir().getAbsolutePath());
        return "内部存储 可用 " + mb(fs.getAvailableBytes())
                + " / 总量 " + mb(fs.getTotalBytes()) + "；"
                + "外置 " + (isExternalStorage() ? "有" : "无");
    }

    private boolean isExternalStorage() {
        File ext = Environment.getExternalStorageDirectory();
        return ext != null && ext.canWrite();
    }

    public String networkInfo() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "网络: 未知";
        android.net.Network active = cm.getActiveNetwork();   // API 23，比 getDefaultNetwork 更稳
        String type = "无连接";
        if (active != null) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "蜂窝";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "以太网";
            }
        }
        return "网络: " + type + (active != null ? "（已连接）" : "（未连接）");
    }

    public String batteryStatus() {
        Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return "无法读取电池状态";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        String state = status == BatteryManager.BATTERY_STATUS_CHARGING ? "充电中"
                : status == BatteryManager.BATTERY_STATUS_FULL ? "已充满"
                : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "放电" : "未知";
        String healthStr = health == BatteryManager.BATTERY_HEALTH_GOOD ? "正常"
                : health == BatteryManager.BATTERY_HEALTH_OVERHEAT ? "过热" : "其它";
        return "电量 " + Math.round(level * 100.0 / scale) + "%，" + state + "，健康: " + healthStr;
    }

    // 剪贴板：读 app 前台期间的剪贴板文本；后台读会拿到空
    public String clipboardGet() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return "剪贴板为空";
        android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        if (item == null) return "剪贴板为空";
        CharSequence cs = item.coerceToText(ctx);
        return cs == null ? "" : cs.toString();
    }

    public String clipboardSet(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return "系统剪贴板不可用";
        cm.setPrimaryClip(android.content.ClipData.newPlainText("happy-agent", text == null ? "" : text));
        return "已写入剪贴板（" + (text == null ? 0 : text.length()) + " 字符）";
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
