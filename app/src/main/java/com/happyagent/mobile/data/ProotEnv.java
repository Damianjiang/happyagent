package com.happyagent.mobile.data;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// proot 容器环境（免 root，非 Termux 纯 app 也能跑）。
// 设计要点（按需求）：
//  1) 环境不进 APK：proot 二进制 + Alpine rootfs 全部运行时下载部署，包体保持小；
//  2) 多镜像源回退链：国内（中科大/清华 alpine）+ 官方 CDN + proot-me，哪个通走哪个；
//  3) 多线程分片下载（Range 分片并行），下载支持断点续传（.part 分片 + 随机写续点）；
//  4) 服务端不支持 Range / 某分片反复失败 → 自动退回单线程顺序下载（仍从断点继续）。
//  5) 部署完真跑一次探活（proot -0 -r rootfs /bin/sh -c id），SELinux 不允许就诚实报不可执行。
public final class ProotEnv {

    private static final String TAG = "ProotEnv";
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;

    // 状态机
    public static final int STATUS_NONE = 0;        // 未部署
    public static final int STATUS_DOWNLOADING = 1; // 部署中（下载/解压）
    public static final int STATUS_READY = 2;       // 就绪且探活通过
    public static final int STATUS_FAILED = 3;      // 部署失败或探活不过

    private static volatile ProotEnv instance;

    private final File dir;            // filesDir/proot
    private final File prootBin;       // proot 二进制
    private final File rootfs;         // 解压后的 distro 根
    private final File cacheTar;       // 下载的 rootfs tar.gz 缓存
    private final File readyMark;      // 探活通过标记
    private final int abi;             // 按设备 ABI 选二进制/rootfs

    // 部署进行中的控制
    private volatile ExecutorService deployPool;
    private volatile boolean cancelDeploy;

    public interface Progress {
        // phase: "proot" / "rootfs" / "extract" / "probe"；pct 0~100
        void on(String phase, int pct, String msg);
        void onDone(boolean ok, String msg);
    }

    private ProotEnv(Context ctx) {
        File base = new File(ctx.getFilesDir(), "proot");
        if (!base.exists()) base.mkdirs();
        this.dir = base;
        this.prootBin = new File(base, "proot");
        this.rootfs = new File(base, "rootfs");
        this.cacheTar = new File(new File(base, "cache"), "minirootfs.tar.gz");
        this.readyMark = new File(base, ".ready");
        this.abi = resolveAbi();
    }

    public static synchronized ProotEnv get(Context ctx) {
        if (instance == null) instance = new ProotEnv(ctx.getApplicationContext());
        return instance;
    }

    // 按设备 ABI 决定架构。aarch64/arm 有对应 alpine 与 proot；x86_64 只能跑 x86 rootfs（无 QEMU 则提示）。
    private static int resolveAbi() {
        if (Build.SUPPORTED_ABIS == null) return 1; // armv7
        for (String a : Build.SUPPORTED_ABIS) {
            if ("aarch64".equals(a)) return 2;
            if ("x86_64".equals(a)) return 3;
            if ("arm64-v8a".equals(a)) return 2;
        }
        return 1; // 默认 armv7
    }

    public String abiName() {
        switch (abi) {
            case 2: return "aarch64";
            case 3: return "x86_64";
            default: return "armv7";
        }
    }

    public boolean isReady() {
        return readyMark.exists() && prootBin.exists() && rootfs.isDirectory();
    }

    // 部署是否进行中（download 线程池还活着）
    public boolean isDeploying() {
        ExecutorService p = deployPool;
        return p != null && !p.isShutdown();
    }

    // 未部署/下载中/就绪/失败；下载中由 deploy() 调用方记，这里返回落盘态
    public int status() {
        if (isReady()) return STATUS_READY;
        if (cacheTar.exists() || prootBin.exists()) return STATUS_NONE; // 有半成品
        return STATUS_NONE;
    }

    public String statusLabel() {
        switch (status()) {
            case STATUS_READY: return "已就绪（" + abiName() + "）";
            case STATUS_NONE:  return "未部署";
            default:           return "未部署";
        }
    }

    // ---- 一键部署：下载 proot + rootfs → 解压 → 探活 ----

    public void deploy(final Progress p) {
        if (deployPool != null) {
            if (p != null) p.onDone(false, "部署已在进行");
            return;
        }
        cancelDeploy = false;
        deployPool = Executors.newFixedThreadPool(5); // 分片并行 + 编排
        final ExecutorService pool = deployPool;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (report(p, "proot", 0, "下载 proot 二进制…")) {
                        downloadProot(pool, p);
                    }
                    if (cancelDeploy) throw new Exception("已取消");
                    if (report(p, "rootfs", 0, "下载容器 rootfs…")) {
                        downloadRootfs(pool, p);
                    }
                    if (cancelDeploy) throw new Exception("已取消");
                    if (report(p, "extract", 0, "解压 rootfs…")) {
                        extract(cacheTar, rootfs, p);
                    }
                    if (cancelDeploy) throw new Exception("已取消");
                    if (report(p, "probe", 0, "探活容器…")) {
                        String probe = probeExec();
                        if (!probe.startsWith("uid=")) {
                            throw new Exception("容器不可执行（" + probe + "）。"
                                    + "多因 SELinux 禁止 app 执行私有目录二进制；可改用 Termux 内的 proot。");
                        }
                    }
                    readyMark.createNewFile();
                    if (p != null) p.onDone(true, "容器环境已就绪");
                } catch (Exception e) {
                    err = e.getMessage();
                    Log.w(TAG, "deploy failed", e);
                    if (cancelDeploy && err == null) err = "已取消";
                    if (p != null) p.onDone(false, err == null ? "部署失败" : err);
                } finally {
                    pool.shutdown();
                    deployPool = null;
                }
            }
        });
    }

    public void cancelDeploy() {
        cancelDeploy = true;
        ExecutorService pool = deployPool;
        if (pool != null) pool.shutdownNow(); // 打断分片下载线程
    }

    private boolean report(Progress p, String phase, int pct, String msg) {
        if (p != null) p.on(phase, pct, msg);
        return !cancelDeploy;
    }

    private void downloadProot(ExecutorService pool, Progress p) throws Exception {
        String arch = (abi == 3) ? "x86_64" : (abi == 2 ? "aarch64" : "arm");
        // 多源回退链：官方 proot-me release → 国内 ghproxy 前缀
        String[] chain = {
                "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-" + arch + "-static",
                "https://mirror.ghproxy.com/https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-" + arch + "-static",
                "https://ghproxy.net/https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-" + arch + "-static"
        };
        ProotDownloader.Progress l = prootProgress(p);
        long total = 0;
        for (int i = 0; i < chain.length; i++) {
            try {
                total = ProotDownloader.download(chain[i], prootBin, l);
                prootBin.setExecutable(true);
                return;
            } catch (Exception e) {
                if (i == chain.length - 1) throw e;
            }
        }
    }

    private void downloadRootfs(ExecutorService pool, Progress p) throws Exception {
        String arch = (abi == 3) ? "x86_64" : (abi == 2 ? "aarch64" : "arm");
        // 源链：国内镜像「latest/minirootfs.tar.gz」优先（实测 USTC/TUNA 通），再官方 CDN 多版本回退。
        // 全是 .tar.gz（gzip+tar），extract 直接解。任一 200 即用，全失败才抛（诚实报「镜像不可达」）。
        String[] chain = {
                "https://mirrors.ustc.edu.cn/alpine/minirootfs/" + arch + "/latest/minirootfs.tar.gz",
                "https://mirrors.tuna.tsinghua.edu.cn/alpine/minirootfs/" + arch + "/latest/minirootfs.tar.gz",
                "https://mirrors.pku.edu.cn/alpine/minirootfs/" + arch + "/latest/minirootfs.tar.gz",
                "https://mirrors.aliyun.com/alpine/minirootfs/" + arch + "/latest/minirootfs.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/minirootfs/" + arch + "/latest/minirootfs.tar.gz",
                // 官方按版本再备几路（版本号 3.23→3.20，取存在的）
                "https://dl-cdn.alpinelinux.org/alpine/minirootfs/" + arch + "/3.22.1/alpine-minirootfs-3.22.1-" + arch + ".tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/minirootfs/" + arch + "/3.21.1/alpine-minirootfs-3.21.1-" + arch + ".tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/minirootfs/" + arch + "/3.20.3/alpine-minirootfs-3.20.3-" + arch + ".tar.gz",
        };
        ProotDownloader.Progress l = rootfsProgress(p);
        cacheTar.getParentFile().mkdirs();
        Exception last = null;
        for (int i = 0; i < chain.length; i++) {
            try {
                long total = ProotDownloader.download(chain[i], cacheTar, l);
                // 下载到的若是 HTML（404 页常被当成功字节流），校验 gzip 魔数再认
                if (!looksLikeGzip(cacheTar)) {
                    cacheTar.delete();
                    last = new Exception("非 gzip 内容: " + chain[i]);
                    continue;
                }
                return;
            } catch (Exception e) {
                last = e;
                if (i == chain.length - 1) throw last;
            }
        }
        throw (last != null) ? last : new Exception("rootfs 下载失败（所有镜像不可达）");
    }

    // 文件头是否 gzip（1f 8b），避免把 404 的 HTML 当成功
    private static boolean looksLikeGzip(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[2];
            int r = in.read(b);
            in.close();
            return r == 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b;
        } catch (Exception e) {
            return false;
        }
    }

    private ProotDownloader.Progress prootProgress(final Progress p) {
        return new ProotDownloader.Progress() {
            @Override
            public int progress(long got, long total) {
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (p != null) p.on("proot", pct, "proot " + got / 1024 + "/" + (total > 0 ? total / 1024 : -1) + " KB");
                return 0;
            }
        };
    }

    private ProotDownloader.Progress rootfsProgress(final Progress p) {
        return new ProotDownloader.Progress() {
            @Override
            public int progress(long got, long total) {
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (p != null) p.on("rootfs", pct, "rootfs " + (got / 1024 / 1024) + " MB" + (total > 0 ? " / " + total / 1024 / 1024 : ""));
                return 0;
            }
        };
    }

    // 解压 gzip tar（Java 原生，手写 tar 解析器；防路径穿越）
    private void extract(File tarGz, File dst, Progress p) throws Exception {
        if (dst.exists()) {
            deleteRec(dst);
        }
        dst.mkdirs();
        InputStream in = new java.util.zip.GZIPInputStream(new FileInputStream(tarGz));
        byte[] hdr = new byte[512];
        int n;
        while (true) {
            // 头可能跨读，凑满 512 或 EOF
            int total = 0;
            while (total < 512) {
                int r = in.read(hdr, total, 512 - total);
                if (r < 0) break;
                total += r;
            }
            if (total < 512) break; // EOF
            if (hdr[0] == 0) break; // 两个全 0 头表示结束
            String name = field(hdr, 0, 156);
            int type = hdr[212];
            String typech = String.valueOf((char) type);
            long size = parseOctal(hdr, 124, 12);
            if (typech.equals("0") || typech.equals("\0") || typech.equals("7")) {
                File target = safeJoin(dst, name);
                target.getParentFile().mkdirs();
                if (!typech.equals("5")) {
                    OutputStream out = new FileOutputStream(target);
                    byte[] buf = new byte[8192];
                    long left = size;
                    while (left > 0) {
                        int c = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (c < 0) break;
                        out.write(buf, 0, c);
                        left -= c;
                    }
                    // 补齐 512 边界
                    long skip = (512 - (size % 512)) % 512;
                    long s;
                    while (skip > 0) { s = in.read(); if (s < 0) break; skip--; }
                    out.close();
                }
            } else if (typech.equals("2")) {
                // 符号链接：API 23 没有 createSymbolicLink（那是 26+），用拷贝目标文件代替
                String linkTarget = field(hdr, 157, 100);
                File target = safeJoin(dst, name);
                target.getParentFile().mkdirs();
                long left = size;
                while (left > 0) { int c = in.read(); if (c < 0) break; left--; }
                long skip = (512 - (size % 512)) % 512;
                while (skip > 0) { int c = in.read(); if (c < 0) break; skip--; }
                if (!linkTarget.isEmpty()) {
                    // 相对路径解析（../bin/busybox 等）
                    File srcFile = new File(target.getParentFile(), linkTarget).getAbsoluteFile();
                    if (srcFile.exists() && srcFile.length() < 50 * 1024 * 1024) {
                        try {
                            java.io.FileInputStream fis = new java.io.FileInputStream(srcFile);
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
                            byte[] cb = new byte[8192];
                            int cn;
                            while ((cn = fis.read(cb)) != -1) fos.write(cb, 0, cn);
                            fos.close();
                            fis.close();
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                // 目录 / 其它，跳数据
                long left = size;
                while (left > 0) { int c = in.read(); if (c < 0) break; left--; }
                long skip = (512 - (size % 512)) % 512;
                while (skip > 0) { int c = in.read(); if (c < 0) break; skip--; }
            }
            if (p != null) p.on("extract", 0, "解压 " + name);
        }
        in.close();
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] h, int off, int len) {
        String s = new String(h, off, len, StandardCharsets.US_ASCII).trim();
        if (s.isEmpty()) return 0;
        try {
            // GNU 大端 8 字节编码（size 高位）在极少数超大块时出现；minirootfs 不会，按普通八进制
            return Long.parseLong(s, 8);
        } catch (Exception e) {
            return 0;
        }
    }

    private File safeJoin(File base, String name) {
        File a = new File(base, name).getAbsoluteFile();
        File b = base.getAbsoluteFile();
        if (!a.getPath().startsWith(b.getPath())) throw new SecurityException("非法路径 " + name);
        return a;
    }

    private static void deleteRec(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRec(x);
        f.delete();
    }

    // 探活：真跑一次 proot，能回 uid= 才说明容器能跑（10s 超时防挂死）
    public String probeExec() {
        if (abi == 3) {
            return "x86_64 设备需要 QEMU 才能跑 ARM/其它 rootfs，当前默认 aarch64/arm，暂不支持此架构容器。";
        }
        if (!prootBin.exists()) return "未部署：proot 二进制不存在";
        if (!rootfs.isDirectory()) return "未部署：rootfs 不存在";
        try {
            final Process proc = Runtime.getRuntime().exec(new String[]{
                    prootBin.getAbsolutePath(), "-0",
                    "-r", rootfs.getAbsolutePath(),
                    "-w", "/",
                    rootfs.getAbsolutePath() + "/bin/sh", "-c", "id"
            });
            final java.util.concurrent.ExecutorService p =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            final java.util.concurrent.Future<byte[]> fo = p.submit(() -> readAll(proc.getInputStream()));
            final java.util.concurrent.Future<Boolean> fd = p.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override public Boolean call() throws Exception { proc.waitFor(); return true; }
            });
            boolean done;
            try {
                fd.get(10, java.util.concurrent.TimeUnit.SECONDS);
                done = true;
            } catch (Exception e) {
                done = false;
            }
            if (!done) {
                proc.destroy();
                p.shutdownNow();
                return "探活超时（10s）";
            }
            byte[] out = fo.get(3, java.util.concurrent.TimeUnit.SECONDS);
            p.shutdown();
            String r = new String(out, StandardCharsets.UTF_8).trim();
            return r.isEmpty() ? "探活无输出（SELinux/执行权限被拒）" : r;
        } catch (Exception e) {
            return "探活异常: " + e.getMessage();
        }
    }

    // 在容器里跑一条命令（引擎 shell_proot 工具用）
    public String execInContainer(String command, int timeoutMs) {
        if (!isReady()) {
            return "容器环境未就绪。先在「设置 → 容器环境」点一键部署；部署后会自动下载 proot + Alpine 并探活。";
        }
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                    prootBin.getAbsolutePath(), "-0",
                    "-r", rootfs.getAbsolutePath(),
                    "-w", "/",
                    rootfs.getAbsolutePath() + "/bin/sh", "-c", command
            });
            final Process p = proc;
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(3);
            final java.util.concurrent.Future<String> fo = pool.submit(new Callable<String>() {
                @Override public String call() throws Exception { return readAllStr(p.getInputStream()); }
            });
            final java.util.concurrent.Future<String> fe = pool.submit(new Callable<String>() {
                @Override public String call() throws Exception { return readAllStr(p.getErrorStream()); }
            });
            // API 23 没有 waitFor(long, TimeUnit)（那是 24+），用 Future.get(timeout) 等价
            final java.util.concurrent.Future<Boolean> fDone = pool.submit(new Callable<Boolean>() {
                @Override public Boolean call() throws Exception {
                    p.waitFor();
                    return Boolean.TRUE;
                }
            });
            boolean done;
            try {
                fDone.get(timeoutMs, TimeUnit.MILLISECONDS);
                done = true;
            } catch (Exception e) {
                done = false;
            }
            if (!done) {
                p.destroy();
                pool.shutdownNow();
                return "容器命令超时（" + timeoutMs + "ms）";
            }
            String out = safe(fo), err = safe(fe);
            pool.shutdown();
            StringBuilder sb = new StringBuilder();
            sb.append("exit ").append(p.exitValue()).append('\n');
            if (out != null && !out.trim().isEmpty()) sb.append(out);
            if (err != null && !err.trim().isEmpty()) sb.append("stderr: ").append(err);
            return sb.toString();
        } catch (Exception e) {
            return "容器执行出错: " + e.getMessage();
        }
    }

    private String safe(java.util.concurrent.Future<String> f) {
        try { return f.get(5000, TimeUnit.MILLISECONDS); }
        catch (Exception e) { return ""; }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    private static String readAllStr(InputStream in) throws Exception {
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    // ================= 下载器：多源回退 + 多线程分片 + 断点续传 + 失败退单线程 =================

    public static final class ProotDownloader {
        public interface Progress {
            // 返回非 0 视为请求中止
            int progress(long got, long total);
        }

        // 分片下载写临时文件的目标
        static final class PartState {
            final RandomAccessFile raf;
            final File file;
            final long start;   // 分片在全局的起点
            final long end;     // 分片终点（含）
            PartState(RandomAccessFile r, File f, long s, long e) { raf = r; file = f; start = s; end = e; }
        }

        /**
         * 下载 url 到 out。
         * - 先 HEAD 探测大小与 Range 支持；
         * - 支持 Range 且文件够大 → N 片并行（每片断点续传），任一源/分片彻底失败 → 退单线程顺序（从已有断点续）；
         * - 不支持 Range → 直接单线程顺序（仍断点）。
         * 返回总字节数。
         */
        static long download(String url, File out, Progress p) throws Exception {
            long size = 0;
            boolean range = false;
            HttpURLConnection hc = open(url, null);
            hc.setConnectTimeout(CONNECT_TIMEOUT);
            hc.setReadTimeout(READ_TIMEOUT);
            int hcode = hc.getResponseCode();
            if (hcode == HttpURLConnection.HTTP_MOVED_PERM || hcode == HttpURLConnection.HTTP_MOVED_TEMP
                    || hcode == 307 || hcode == 308) {
                String loc = hc.getHeaderField("Location");
                hc.disconnect();
                hc = open(url, loc);
                hc.setConnectTimeout(CONNECT_TIMEOUT);
                hc.setReadTimeout(READ_TIMEOUT);
                hcode = hc.getResponseCode();
            }
            if (hcode != HttpURLConnection.HTTP_OK && hcode != HttpURLConnection.HTTP_PARTIAL) {
                String body;
                try {
                    java.io.InputStream es = hc.getErrorStream();
                    body = es == null ? "" : readAllStr(es);
                } catch (Exception e) {
                    body = "";
                }
                hc.disconnect();
                throw new Exception("HTTP " + hcode + " 下载 " + url + "（" + body.trim() + "）");
            }
            size = hc.getContentLength();
            if ("bytes".equalsIgnoreCase(hc.getHeaderField("Accept-Ranges"))) range = true;
            hc.disconnect();

            if (!range || size <= 0) {
                return singleThread(url, out, p);
            }

            int N = size < (2L << 20) ? 2 : 4; // 小文件少分片，避免开销
            long chunk = (size + N - 1) / N;
            final long totalSize = size;   // 匿名类内用（size 非 effectively final）
            final List<PartState> parts = new ArrayList<PartState>();
            ExecutorService pool = Executors.newFixedThreadPool(N);
            List<Future<Boolean>> futs = new ArrayList<Future<Boolean>>();
            try {
                for (int i = 0; i < N; i++) {
                    long s = i * chunk;
                    long e = Math.min(s + chunk - 1, size - 1);
                    File part = new File(out.getParentFile(), out.getName() + ".part." + i);
                    part.getParentFile().mkdirs();
                    RandomAccessFile raf = new RandomAccessFile(part, "rw");
                    PartState st = new PartState(raf, part, s, e);
                    parts.add(st);
                    final PartState f = st;
                    futs.add(pool.submit(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            try {
                                downloadPart(url, f, p, totalSize);
                                // 成功：不 close，留给 merge 复用读；失败才由下面清理
                                return Boolean.TRUE;
                            } catch (Exception ex) {
                                // 彻底失败 → 抛出让上层退单线程（part 文件由清理统一删）
                                throw new RuntimeException(ex);
                            }
                        }
                    }));
                }
                // 收集结果；任一片失败 → 退单线程（顺序下载，仍从 out 已有字节续）
                boolean allOk = true;
                for (Future<Boolean> ft : futs) {
                    try { ft.get(READ_TIMEOUT + 60000, TimeUnit.MILLISECONDS); }
                    catch (Exception e) { allOk = false; break; }
                }
                if (allOk) {
                    try {
                        return merge(parts, out, size);   // merge 内 close + delete 各 part
                    } finally {
                        cleanupParts(parts);
                    }
                }
                // 退单线程：关闭已开的 part，顺序下载（out 已存在的字节作为续点）
                cleanupParts(parts);
                return singleThread(url, out, p);
            } catch (Exception e) {
                cleanupParts(parts);
                // 兜底：也退单线程
                return singleThread(url, out, p);
            } finally {
                pool.shutdownNow();
            }
        }

        // 关闭并删除临时分片文件
        private static void cleanupParts(List<PartState> parts) {
            for (PartState st : parts) {
                try { st.raf.close(); } catch (Exception ignored) {}
                st.file.delete();
            }
        }

        // 单个分片 [start,end] 断点续传下载；重试 3 次，彻底失败抛异常（上层退单线程）
        static void downloadPart(String url, PartState st, Progress p, long total) throws Exception {
            // 该分片已下到的位置（断点）
            long absStart = st.start;
            File f = st.file;
            if (f.exists() && f.length() > 0) absStart = st.start + f.length();
            int tries = 0;
            while (absStart <= st.end) {
                HttpURLConnection c = open(url, "bytes=" + absStart + "-" + st.end);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    // 服务端没按 Range 给分片（整段返回），本分片没法续 → 视为不支持分片，抛出让上层退单线程
                    c.disconnect();
                    throw new Exception("server ignored Range, aborting multi-part");
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    c.disconnect();
                    throw new Exception("HTTP " + code + " on range");
                }
                try {
                    InputStream in = c.getInputStream();
                    st.raf.seek(absStart - st.start);   // part 文件只存切片 [start,end]，偏移相对切片起点
                    byte[] buf = new byte[16384];
                    int n;
                    while (absStart <= st.end && (n = in.read(buf)) != -1) {
                        int writeEnd = (int) Math.min(n, st.end - absStart + 1);
                        if (writeEnd > 0) st.raf.write(buf, 0, writeEnd);
                        absStart += writeEnd;
                        if (p != null) p.progress(absStart, total);
                    }
                } finally {
                    c.disconnect();
                }
                // 到这里说明该分片完整
                if (absStart > st.end) return;
                // 断点：网络中断，留着已下字节，重试（最多 3 次，超过退单线程）
                if (++tries > 3) throw new Exception("分片重试 3 次仍失败");
            }
        }

        // 顺序拼接分片到 out（已完整的 out 直接跳过）
        static long merge(List<PartState> parts, File out, long total) throws Exception {
            if (out.exists() && out.length() == total) return total;
            // 若已单线程下过 out 的全量也认
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            long written = 0;
            for (PartState st : parts) {
                RandomAccessFile r = st.raf;
                r.seek(0);   // part 文件只存切片 [start,end]，从 0 起
                long remain = st.end - st.start + 1;
                while (remain > 0) {
                    int n = r.read(buf, 0, (int) Math.min(buf.length, remain));
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    written += n;
                    remain -= n;
                }
            }
            fos.close();
            // 校验总长
            if (out.length() != total) {
                out.delete();
                throw new Exception("分片合并长度不符（" + out.length() + " != " + total + "）");
            }
            // 清临时分片
            for (PartState st : parts) st.file.delete();
            return total;
        }

        // 单线程顺序下载，支持断点续传；中途断流/网络异常 → 从当前已下字节自动续传（最多 N 次重连）
        static long singleThread(String url, File out, Progress p) throws Exception {
            long total = probeSize(url);
            return singleThread(url, out, p, total);
        }

        static long singleThread(String url, File out, Progress p, long total) throws Exception {
            int connects = 0;
            while (true) {
                long absStart = out.exists() ? out.length() : 0;
                HttpURLConnection c = open(url, absStart > 0 ? "bytes=" + absStart + "-" : null);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK && absStart > 0) {
                    // 服务端不支持 Range 续传，得从头
                    absStart = 0;
                    c.disconnect();
                    c = open(url, null);
                    c.setConnectTimeout(CONNECT_TIMEOUT);
                    c.setReadTimeout(READ_TIMEOUT);
                    code = c.getResponseCode();
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    c.disconnect();
                    if (++connects > 3) throw new Exception("单线程下载失败（HTTP " + code + "，重试 3 次）");
                    continue;
                }
                try {
                    InputStream in = c.getInputStream();
                    RandomAccessFile raf = new RandomAccessFile(out, "rw");
                    raf.seek(absStart);
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) != -1) {
                            raf.write(buf, 0, n);
                            absStart += n;
                            if (p != null) p.progress(absStart, total);
                        }
                    } finally {
                        try { raf.close(); } catch (Exception ignored) {}
                    }
                    return absStart; // 读满（EOF）→ 成功
                } catch (Exception e) {
                    // 断流/超时：已写字节保留在 out（RandomAccessFile 实时落盘），重连从断点续
                    c.disconnect();
                    if (++connects > 8) throw new Exception("单线程下载断流，重试 8 次仍失败：" + e.getMessage());
                    continue; // absStart 会在循环顶部重新从 out.length() 取
                }
            }
        }

        // HEAD 探测总大小（失败返回 -1，分片/进度会退化为不显示百分比）
        static long probeSize(String url) {
            try {
                HttpURLConnection c = open(url, null);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(CONNECT_TIMEOUT);
                c.setRequestMethod("HEAD");
                long len = c.getContentLength();
                int code = c.getResponseCode();
                c.disconnect();
                if (code == HttpURLConnection.HTTP_OK) return len;
            } catch (Exception ignored) {}
            return -1;
        }

        // 打开连接；rangeHeader 为 null 则普通 GET；跟随重定向
        static HttpURLConnection open(String url, String rangeHeader) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            if (rangeHeader != null) c.setRequestProperty("Range", rangeHeader);
            return c;
        }
    }
}
