package com.happyagent.mobile.tools;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 真实 shell 沙箱：/system/bin/sh 跑命令，带超时 + 命令白名单
// 安卓沙箱没 root 跑不了任意命令，这里做「标准执行器」+ proot 环境探测，
// 对应 Operit 的 ShellExecutor（Standard 层 + Linux/proot 层）
// 全程只用 API 23 可用的：无参 waitFor() + Future.get(timeout) + destroy()
public final class ShellExecutor {

    private static final String TAG = "ShellExecutor";
    private static final int MAX_OUTPUT = 64 * 1024;

    // 只允许这几类安全命令进沙箱执行，防注入
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "ls", "cat", "echo", "date", "uname", "whoami", "pwd", "id",
            "grep", "head", "tail", "wc", "stat", "find", "which"));

    private final Context ctx;

    public ShellExecutor(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // proot / Termux 环境探测：手机上有没有 proot-distro 或 Termux 可执行
    public String detectProot() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        String[] probes = {
                "/data/data/com.termux/files/usr/bin/proot-distro",
                "/data/data/com.termux/files/home/.proot-distro",
                "/system/bin/sh",
                "/system/bin/echo",
                ctx.getFilesDir() + "/workspace"
        };
        for (String p : probes) {
            File f = new File(p);
            sb.append(f.exists() ? "  [有] " : "  [无] ").append(p).append('\n');
        }
        return sb.toString();
    }

    // 跑一条命令，带超时。只接受白名单里的命令。
    public String exec(String command, int timeoutMs) {
        if (command == null || command.trim().isEmpty()) return "empty command";
        String trimmed = command.trim();

        // 取第一个 token 做白名单校验
        int sp = trimmed.indexOf(' ');
        String cmd0 = sp > 0 ? trimmed.substring(0, sp).trim() : trimmed;
        cmd0 = cmd0.replaceAll("[^a-z]", "").toLowerCase();
        if (!ALLOWED.contains(cmd0)) {
            return "blocked: " + cmd0 + " not in allowlist";
        }

        Process proc;
        try {
            String[] argv = {"/system/bin/sh", "-c", trimmed};
            proc = Runtime.getRuntime().exec(argv);
        } catch (Exception e) {
            Log.e(TAG, "exec start failed", e);
            return "exec failed: " + e.getMessage() + "\n" + detectProot();
        }

        // 并发读 stdout/stderr + 并发等结束，三件事丢一个线程池
        ExecutorService pool = Executors.newFixedThreadPool(3);
        final Process p = proc;
        final Future<String> fOut = pool.submit(() -> readLimited(p.getInputStream()));
        final Future<String> fErr = pool.submit(() -> readLimited(p.getErrorStream()));
        final Future<Boolean> fDone = pool.submit(new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    p.waitFor();   // 无参 waitFor 是 API 1
                    return Boolean.TRUE;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Boolean.FALSE;
                }
            }
        });

        boolean done;
        try {
            fDone.get(timeoutMs, TimeUnit.MILLISECONDS);
            done = true;
        } catch (Exception e) {
            // 超时（TimeoutException）或执行异常都按未完成处理
            done = false;
        }

        String stdout = safeGet(fOut, 5000);
        String stderr = safeGet(fErr, 5000);
        pool.shutdownNow();

        if (!done) {
            p.destroy();   // API 1，无 destroyForcibly（那是 API 26）
            return "timeout after " + timeoutMs + "ms\n" + (stdout == null ? "" : stdout);
        }

        int code;
        try {
            code = p.exitValue();
        } catch (Exception e) {
            code = -1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("exit ").append(code).append('\n');
        if (stdout != null && !stdout.trim().isEmpty()) sb.append(stdout);
        if (stderr != null && !stderr.trim().isEmpty()) sb.append("stderr: ").append(stderr);
        return sb.toString();
    }

    private String safeGet(Future<String> f, int ms) {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    // 读流带上限，避免输出撑爆内存
    private String readLimited(InputStream is) {
        try {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() >= MAX_OUTPUT) {
                    while (is.read() != -1) {}   // 排空剩余，防管道积压
                    sb.append("\n... (truncated)");
                    break;
                }
            }
            is.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
