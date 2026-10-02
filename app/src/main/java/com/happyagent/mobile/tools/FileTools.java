package com.happyagent.mobile.tools;

import android.content.Context;
import android.util.Log;

import com.happyagent.mobile.model.Models;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 真实文件工具：读/写/列目录/找文件/grep/文件信息。
// 全走 java.io.File（安卓6 无 java.nio.file），在私有目录沙箱内做路径校验。
public final class FileTools {

    private static final String TAG = "FileTools";
    private static final int MAX_LIST = 200;
    private static final int MAX_GREP = 100;
    private static final int MAX_FIND_DEPTH = 4;
    private static final int MAX_CONTENT = 32 * 1024;

    private final Context ctx;
    // 沙箱根：只能在 app 私有文件目录里操作，防越权
    private final File sandboxRoot;
    // 附件目录：落在 workspace 内，agent 的 file_read 能直接读到用户发的文件
    private final File attachRoot;

    private static volatile boolean seeded;

    public FileTools(Context context) {
        this.ctx = context.getApplicationContext();
        this.sandboxRoot = new File(ctx.getFilesDir(), "workspace");
        if (!sandboxRoot.exists()) sandboxRoot.mkdirs();
        this.attachRoot = new File(sandboxRoot, "attachments");
        if (!attachRoot.exists()) attachRoot.mkdirs();
        seed();
    }

    // 首次进入时建一个 WELCOME.txt，让文件页一开始就有内容可看
    private void seed() {
        if (seeded) return;
        synchronized (FileTools.class) {
            if (seeded) return;
            File welcome = new File(sandboxRoot, "WELCOME.txt");
            if (!welcome.exists()) {
                try {
                    OutputStreamWriter w = new OutputStreamWriter(
                            new FileOutputStream(welcome), StandardCharsets.UTF_8);
                    w.write("Happy Agent 工作区\n"
                            + "================\n"
                            + "\n"
                            + "目录说明：\n"
                            + "  - 浏览 / 新建 / 编辑 / 复制 / 移动 / 删除文件与目录\n"
                            + "  - 压缩(zip) / 解压(unzip)\n"
                            + "  - 按文件名搜索\n"
                            + "  - 把文件发给智能体处理\n"
                            + "\n"
                            + "智能体的 file_read / file_write 等工具作用在本目录（沙箱校验）。\n");
                    w.close();
                } catch (Exception e) {
                    Log.e(TAG, "seed", e);
                }
            }
            seeded = true;
        }
    }

    public String getWorkspace() {
        return sandboxRoot.getAbsolutePath();
    }

    // 供文件管理器/编辑器浏览用的 workspace 绝对路径
    public String workspacePath() {
        return sandboxRoot.getAbsolutePath();
    }

    // 编辑器专用：读整个文件文本；文件不存在/是新文件返回 ""（而非 read() 的"not found"提示串）
    public String readContent(String path) {
        File f = resolveSafe(path);
        if (!f.exists() || f.isDirectory()) return "";
        try {
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int read = in.read(data, 0, (int) f.length());
            in.close();
            if (read < 0) return "";
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "readContent", e);
            return "";
        }
    }

    // 把外部 content URI（相册/文件）拷贝进沙箱附件目录，返回 Attachment。
    // 用 android.util.Base64/流拷贝，纯 API 1~23，图片不直接解码避免 OOM。
    public Models.Attachment saveAttachment(android.content.ContentResolver resolver,
                                             android.net.Uri uri, String displayName) {
        String safeName = displayName == null || displayName.trim().isEmpty()
                ? String.valueOf(System.currentTimeMillis())
                : displayName.trim();
        safeName = safeName.replaceAll("[^A-Za-z0-9._\\-]", "_");
        File dest = new File(attachRoot, System.currentTimeMillis() + "_" + safeName);
        java.io.InputStream in;
        try {
            in = resolver.openInputStream(uri);
        } catch (Exception e) {
            return null;
        }
        if (in == null) return null;
        try {
            java.io.OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            in.close();
            String mime = resolver.getType(uri);
            return new Models.Attachment(safeName,
                    mime == null ? "application/octet-stream" : mime,
                    dest.getAbsolutePath(), dest.length());
        } catch (Exception e) {
            Log.e(TAG, "saveAttachment", e);
            return null;
        }
    }

    // 路径校验：把传入路径解析后确认它落在沙箱内，否则拒绝
    private File resolveSafe(String path) {
        File abs = new File(path).getAbsoluteFile();
        String root = sandboxRoot.getAbsolutePath();
        String target = abs.getAbsolutePath();
        if (!target.equals(root) && !target.startsWith(root + File.separator)) {
            throw new SecurityException("path out of workspace: " + path);
        }
        return abs;
    }

    private boolean inSandbox(File f) {
        String root = sandboxRoot.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.equals(root) || p.startsWith(root + File.separator);
    }

    public String read(String path) {
        File f = resolveSafe(path);
        if (!f.exists()) return "not found: " + path;
        if (f.isDirectory()) return "is a directory";
        if (f.length() > MAX_CONTENT) return "file too large (" + f.length() + " bytes)";
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "read", e);
            return "read error: " + e.getMessage();
        }
    }

    public String write(String path, String content) {
        File f = resolveSafe(path);
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8));
            w.write(content == null ? "" : content);
            w.close();
            return "wrote " + f.length() + " bytes to " + f.getName();
        } catch (IOException e) {
            Log.e(TAG, "write", e);
            return "write error: " + e.getMessage();
        }
    }

    public String list(String path) {
        File dir = resolveSafe(path);
        if (!dir.exists() || !dir.isDirectory()) return "not a dir: " + path;
        File[] files = dir.listFiles();
        List<String> out = new ArrayList<String>();
        if (files == null) return "list failed";
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (File c : files) {
            if (n++ >= MAX_LIST) { sb.append("... (truncated)\n"); break; }
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.isDirectory() ? "[D] " : "[F] ").append(c.getName());
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    // ---- UI 文件管理器用的结构化方法（供界面浏览/增删改，不喂模型） ----

    public static class FileEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final boolean isDir;
        public final long size;          // 目录为 0
        public final long lastModified;
        public final String absPath;

        public FileEntry(String name, boolean isDir, long size, long lastModified, String absPath) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
            this.lastModified = lastModified;
            this.absPath = absPath;
        }

        @Override
        public String toString() {
            return name + (isDir ? "/" : "");
        }
    }

    // 结构化列目录：目录在前，其余按文件名不区分大小写排序；返回绝对路径供后续操作
    public List<FileEntry> listEntries(String path) {
        File dir = resolveSafe(path);
        if (!dir.exists() || !dir.isDirectory()) return new ArrayList<FileEntry>();
        File[] files = dir.listFiles();
        if (files == null) return new ArrayList<FileEntry>();
        List<FileEntry> out = new ArrayList<FileEntry>();
        for (File f : files) {
            out.add(new FileEntry(f.getName(), f.isDirectory(),
                    f.isDirectory() ? 0 : f.length(), f.lastModified(), f.getAbsolutePath()));
        }
        Collections.sort(out, new java.util.Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    // 删除文件/目录（沙箱内）；workspace 根不可删
    public String delete(String path) {
        File f = resolveSafe(path);
        if (!f.exists()) return "not found: " + path;
        if (f.equals(sandboxRoot)) return "cannot delete workspace root";
        deleteRec(f);
        return f.exists() ? "delete failed: " + path : "deleted " + f.getName();
    }

    // 新建目录（沙箱内，可多级）
    public String mkdir(String path) {
        File f = resolveSafe(path);
        if (f.exists()) return "exists: " + path;
        return f.mkdirs() ? "created dir " + f.getName() : "mkdir failed: " + path;
    }

    // 递归按文件名模糊搜索（工作区内），返回命中文件的绝对路径；限深度/数量防失控
    public List<String> searchName(String path, String query) {
        File start = resolveSafe(path);
        List<String> out = new ArrayList<String>();
        String q = query == null ? "" : query.trim().toLowerCase();
        if (!q.isEmpty()) searchNameRec(start, q, 0, out);
        return out;
    }

    private void searchNameRec(File dir, String q, int depth, List<String> out) {
        if (depth > 6 || out.size() >= 200) return;
        if (!inSandbox(dir)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File c : files) {
            if (out.size() >= 200) return;
            if (c.getName().toLowerCase().contains(q)) out.add(c.getAbsolutePath());
        }
        for (File c : files) {
            if (out.size() >= 200) return;
            if (c.isDirectory()) searchNameRec(c, q, depth + 1, out);
        }
    }

    public String find(String path, String name) {
        File start = resolveSafe(path);
        List<String> out = new ArrayList<String>();
        findRec(start, name, 0, out);
        return join(out, "no match");
    }

    private void findRec(File dir, String name, int depth, List<String> out) {
        if (depth > MAX_FIND_DEPTH) return;
        if (!inSandbox(dir)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File c : files) {
            if (out.size() >= MAX_LIST) return;
            if (c.getName().equals(name)) out.add(c.getAbsolutePath());
        }
        for (File c : files) {
            if (out.size() >= MAX_LIST) return;
            if (c.isDirectory()) findRec(c, name, depth + 1, out);
        }
    }

    public String grep(String path, String keyword) {
        File f = resolveSafe(path);
        if (!f.exists()) return "not found: " + path;
        List<String> out = new ArrayList<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            int lineNo = 0;
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.contains(keyword)) {
                    out.add(lineNo + ": " + line.trim());
                    if (out.size() >= MAX_GREP) { out.add("... (truncated)"); break; }
                }
            }
            r.close();
        } catch (IOException e) {
            Log.e(TAG, "grep", e);
            return "grep error: " + e.getMessage();
        }
        return join(out, "no match");
    }

    // 安卓6 没有 String.join，自己拼
    private static String join(List<String> parts, String empty) {
        if (parts.isEmpty()) return empty;
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    public String info(String path) {
        File f = resolveSafe(path);
        if (!f.exists()) return "not found: " + path;
        return f.getName() + (f.isDirectory() ? " [dir]" : " [file " + f.length() + " bytes]");
    }

    // ---- 文件操作：源和目标都先过沙箱校验 ----

    public String exists(String path) {
        File f = resolveSafe(path);
        return f.exists() ? "exists: " + path
                : (f.getParentFile() != null && f.getParentFile().exists() ? "not found: " + path : "parent missing: " + path);
    }

    public String move(String src, String dst) {
        File s = resolveSafe(src);
        File d = resolveSafe(dst);
        if (!s.exists()) return "not found: " + src;
        if (d.getParentFile() != null && !d.getParentFile().exists() && !d.getParentFile().mkdirs())
            return "cannot create target parent: " + dst;
        // renameTo 跨设备会返回 false，此时用「拷贝 + 删除源」完成 move（标准语义）
        if (s.renameTo(d)) return "moved " + src + " -> " + dst;
        if (copyRec(s, d)) {
            deleteRec(s);
            return "moved (via copy) " + src + " -> " + dst;
        }
        return "move failed: " + src;
    }

    public String copy(String src, String dst) {
        File s = resolveSafe(src);
        File d = resolveSafe(dst);
        if (!s.exists()) return "not found: " + src;
        if (d.getParentFile() != null && !d.getParentFile().exists() && !d.getParentFile().mkdirs())
            return "cannot create target parent: " + dst;
        return copyRec(s, d) ? "copied " + src + " -> " + dst : "copy failed: " + src;
    }

    // 把文件（或整棵目录树）打成一个 zip 文件
    public String zip(String src, String dst) {
        File s = resolveSafe(src);
        File d = resolveSafe(dst);
        if (!s.exists()) return "not found: " + src;
        if (d.getParentFile() != null && !d.getParentFile().exists() && !d.getParentFile().mkdirs())
            return "cannot create target parent: " + dst;
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new FileOutputStream(d));
            if (s.isDirectory()) {
                addDirToZip(s, s.getParentFile() == null ? "" : s.getName() + "/", zos);
            } else {
                putFileToZip(s, s.getName(), zos);
            }
            zos.close();
            zos = null;
            return "zipped " + src + " -> " + d.getName() + " (" + d.length() + " bytes)";
        } catch (IOException e) {
            Log.e(TAG, "zip", e);
            return "zip error: " + e.getMessage();
        } finally {
            if (zos != null) {
                try { zos.close(); } catch (Exception ignored) {}
            }
        }
    }

    // 把 zip 解到目录
    public String unzip(String src, String dst) {
        File s = resolveSafe(src);
        File dir = resolveSafe(dst);
        if (!s.exists()) return "not found: " + src;
        if (!dir.exists() && !dir.mkdirs()) return "cannot create dest dir: " + dst;
        java.util.zip.ZipInputStream zis = null;
        int count = 0;
        try {
            zis = new java.util.zip.ZipInputStream(new FileInputStream(s));
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                // 防 zip 滑出：目标必须落在 dst 目录内
                File out = new File(dir, ze.getName()).getCanonicalFile();
                String root = dir.getCanonicalPath();
                if (!out.getPath().startsWith(root + File.separator) && !out.getPath().equals(root)) {
                    throw new SecurityException("zip entry out of dir: " + ze.getName());
                }
                if (ze.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    java.io.FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close();
                    count++;
                }
                zis.closeEntry();
            }
            zis.close();
            zis = null;
            return "unzipped " + count + " file(s) into " + dst;
        } catch (IOException e) {
            Log.e(TAG, "unzip", e);
            return "unzip error: " + e.getMessage();
        } finally {
            if (zis != null) {
                try { zis.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---- 上面用到的私有递归助手 ----

    private boolean copyRec(File s, File d) {
        try {
            if (s.isDirectory()) {
                if (!d.exists() && !d.mkdirs()) return false;
                File[] children = s.listFiles();
                if (children == null) return false;
                for (File c : children) if (!copyRec(c, new File(d, c.getName()))) return false;
                return true;
            }
            if (d.getParentFile() != null && !d.getParentFile().exists() && !d.getParentFile().mkdirs())
                return false;
            FileInputStream in = new FileInputStream(s);
            FileOutputStream out = new FileOutputStream(d);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            out.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyRec", e);
            return false;
        }
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRec(c);
        }
        f.delete();
    }

    private void addDirToZip(File dir, String prefix, java.util.zip.ZipOutputStream zos)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) addDirToZip(c, prefix + c.getName() + "/", zos);
            else putFileToZip(c, prefix + c.getName(), zos);
        }
    }

    private void putFileToZip(File f, String name, java.util.zip.ZipOutputStream zos)
            throws IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        in.close();
        zos.closeEntry();
    }
}
