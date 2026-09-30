package com.happyagent.mobile;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.happyagent.mobile.ui.CrashActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

// 兜底逻辑：任何线程上没接住的异常，直接跳崩溃页
// 页面上有完整日志和一键复制，现场直接发回开发侧就行
// 注意这里不转交系统默认 handler，否则进程先被杀，崩溃页根本来不及显示
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Application app;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);

    private CrashHandler(Application app) {
        this.app = app;
    }

    public static void install(Application app) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable t) {
        if (!inProgress.compareAndSet(false, true)) return;
        try {
            openCrashPage(buildReport(thread.getName(), t));
        } finally {
            inProgress.set(false);
        }
    }

    // 业务代码里 catch 到了想手动上报的，走这里
    public static void showFrom(Throwable t) {
        Application app = HappyAgentApplication.get();
        if (app == null) return;
        openCrashPage(buildReport("manual-report", t));
    }

    public static void showFrom(String report) {
        Application app = HappyAgentApplication.get();
        if (app == null) return;
        openCrashPage(report);
    }

    // 崩溃页"返回首页"用：清掉现场，冷启一个干净的实例
    public static void finishAndGoHome(Context context) {
        if (context == null) return;
        Context root = context.getApplicationContext();
        Intent intent = root.getPackageManager()
                .getLaunchIntentForPackage(root.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        }
    }

    private static void openCrashPage(String report) {
        Application app = HappyAgentApplication.get();
        if (app == null) return;
        Intent intent = new Intent(app, CrashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(CrashActivity.EXTRA_REPORT, report);
        app.startActivity(intent);
    }

    private static String buildReport(String threadName, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== AGENT CRASH LOG ===");
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        pw.println("系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("构建: " + Build.FINGERPRINT);
        pw.println();
        pw.println("出错的线程: " + threadName);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
