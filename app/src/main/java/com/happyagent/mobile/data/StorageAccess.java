package com.happyagent.mobile.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import com.happyagent.mobile.model.Models.Attachment;
import com.happyagent.mobile.tools.FileTools;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 文件访问统一走 SAF：用户选一次文件夹即持久授权，跨 API 23+ 可用。
// 不用遗留 READ/WRITE_EXTERNAL_STORAGE——SAF 不依赖它们。
public final class StorageAccess {

    public StorageAccess() {}

    // ---- 授权：存 root tree uri，访问前重新取持久权限 ----
    public static Uri getRoot(Context ctx) {
        String s = new Prefs(ctx).getStringStorage();
        if (TextUtils.isEmpty(s)) return null;
        return Uri.parse(s);
    }

    public static void grant(Context ctx, Uri treeUri) {
        // 取持久读写权限，重启后依然能用
        ctx.getContentResolver().takePersistableUriPermission(treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        new Prefs(ctx).putStringStorage(treeUri.toString());
    }

    public static boolean isGranted(Context ctx) {
        // 存了 tree uri 即视为已授权（grant 时已 takePersistable，重启系统会保持）
        return getRoot(ctx) != null;
    }

    // 根目录 docId；用 API 19 的 getDocumentId 取 tree 根（API 26 的 getRootDocumentId 老机没有）
    public static String rootDocumentId(Context ctx) {
        Uri root = getRoot(ctx);
        return root == null ? null : DocumentsContract.getDocumentId(root);
    }

    // 列某目录子项；parentId 传 rootDocumentId 或某子目录 docId
    public static List<Entry> listChildren(Context ctx, String parentId) {
        List<Entry> out = new ArrayList<Entry>();
        Uri root = getRoot(ctx);
        if (root == null) return out;
        // 2 参 buildChildDocumentsUri(authority, parentDocId) 是 API 19，跨老机可用
        Uri q = DocumentsContract.buildChildDocumentsUri(root.getAuthority(), parentId);
        try {
            Cursor c = ctx.getContentResolver().query(q, null, null, null, null);
            if (c == null) return out;
            try {
                int iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int iType = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                int iFlags = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.docId = c.getString(iId);
                    e.name = c.getString(iName);
                    e.size = iSize >= 0 ? c.getLong(iSize) : 0;
                    // FLAG_DIR 的值为 2（DocumentsContract.Document.FLAG_DIR 部分老机没有，用字面量）
                    e.isDir = iFlags >= 0 && (c.getInt(iFlags) & 2) != 0;
                    e.mime = iType >= 0 ? c.getString(iType) : "";
                    out.add(e);
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {
        }
        // 目录排前、按名排序；用 Collections.sort（List.sort 是 API 24，老机崩）
        Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static Uri docUri(Context ctx, String docId) {
        // 2 参 buildDocumentUri(authority, docId) 是 API 19，跨老机可用
        Uri root = getRoot(ctx);
        return root == null ? null : DocumentsContract.buildDocumentUri(root.getAuthority(), docId);
    }

    // 授权根显示名：查根 uri 的 DISPLAY_NAME 列（API 19）
    public static String rootDisplayName(Context ctx) {
        Uri root = getRoot(ctx);
        if (root == null) return "已授权根目录";
        try {
            Cursor c = ctx.getContentResolver().query(root,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return "已授权根目录";
    }

    // ---- 文本读写（编辑器用）----
    public static String readText(Context ctx, String docId) {
        Uri u = docUri(ctx, docId);
        InputStream in = null;
        BufferedReader r = null;
        try {
            in = ctx.getContentResolver().openInputStream(u);
            if (in == null) return "";
            r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    public static String writeText(Context ctx, String docId, String content) {
        Uri u = docUri(ctx, docId);
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(
                    ctx.getContentResolver().openOutputStream(u), StandardCharsets.UTF_8);
            w.write(content == null ? "" : content);
            w.flush();
            return "已保存";
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    // 把选中文件拷进 agent 工作区附件目录，返回 Attachment（file_read 可直接读）
    public static Attachment sendToAgent(Context ctx, String docId, String displayName) {
        FileTools ft = new FileTools(ctx);
        Attachment a = ft.saveAttachment(ctx.getContentResolver(), docUri(ctx, docId), displayName);
        return a;
    }

    public static class Entry {
        public String docId;
        public String name;
        public String mime;
        public long size;
        public boolean isDir;
    }
}
