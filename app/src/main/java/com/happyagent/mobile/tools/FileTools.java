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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public FileTools(Context context) {
        this.ctx = context.getApplicationContext();
        this.sandboxRoot = new File(ctx.getFilesDir(), "workspace");
        if (!sandboxRoot.exists()) sandboxRoot.mkdirs();
        this.attachRoot = new File(sandboxRoot, "attachments");
        if (!attachRoot.exists()) attachRoot.mkdirs();
    }

    public String getWorkspace() {
        return sandboxRoot.getAbsolutePath();
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
}
