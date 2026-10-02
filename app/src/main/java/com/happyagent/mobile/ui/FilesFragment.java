package com.happyagent.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.StorageAccess;
import com.happyagent.mobile.model.Models;
import com.happyagent.mobile.tools.FileTools;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 文件管理器：两个来源——「工作区」是 app 私有目录，可增删改/压缩/解压/搜索/发给智能体；
// 「我的目录」走 SAF 授权，浏览并把你自己的文件发给智能体。默认停在「工作区」。
public class FilesFragment extends Fragment {

    private static final int RC_PICK_FOLDER = 1;
    private static final String PROVIDER_AUTHORITY = "com.happyagent.mobile.fileprovider";
    private static final int MODE_WS = 0, MODE_SAF = 1;
    private int mode = MODE_WS;

    private FileTools fileTools;
    private RecyclerView recycler;
    private TextView empty;
    private RowAdapter adapter;

    private View tabWs, tabSaf;
    // 工作区
    private View wsToolbar, wsCrumb, wsSearch;
    private TextView wsFolder, wsCount;
    private String wsCurrent;
    // 我的目录(SAF)
    private View safGrant, safCrumb;
    private TextView safFolder, safCount;
    private final List<String> safPath = new ArrayList<String>();
    private final List<String> safPathNames = new ArrayList<String>();
    private List<StorageAccess.Entry> safItems = new ArrayList<StorageAccess.Entry>();

    // 统一展示模型：跨工作区/SAF 复用同一行
    private static final class Row {
        String name, meta, icon, mime;
        boolean isDir;
        boolean fromSaf;
        String absPath;   // 工作区
        String docId;     // SAF

        void iconEmoji() {
            String n = name == null ? "" : name.toLowerCase();
            if (isDir) icon = "📁";
            else if (n.endsWith(".zip")) icon = "🗜";
            else if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) icon = "🖼";
            else icon = "📄";
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_files, container, false);
        fileTools = new FileTools(v.getContext());

        recycler = v.findViewById(R.id.files_recycler);
        empty = v.findViewById(R.id.files_empty);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new RowAdapter(new ArrayList<Row>());
        recycler.setAdapter(adapter);

        tabWs = v.findViewById(R.id.files_tab_ws);
        tabSaf = v.findViewById(R.id.files_tab_saf);
        tabWs.setOnClickListener(x -> switchTo(MODE_WS));
        tabSaf.setOnClickListener(x -> switchTo(MODE_SAF));

        wsToolbar = v.findViewById(R.id.files_ws_toolbar);
        wsCrumb = v.findViewById(R.id.files_ws_crumb);
        wsSearch = v.findViewById(R.id.files_ws_search);
        wsFolder = v.findViewById(R.id.files_ws_folder);
        wsCount = v.findViewById(R.id.files_ws_count);
        v.findViewById(R.id.files_ws_up).setOnClickListener(x -> wsUp());
        v.findViewById(R.id.files_ws_newfile).setOnClickListener(x -> wsNewFile());
        v.findViewById(R.id.files_ws_newdir).setOnClickListener(x -> wsNewDir());
        EditText wsS = (EditText) wsSearch;
        wsS.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int cn) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int cn) {
                if (mode == MODE_WS) loadWs();
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });

        safGrant = v.findViewById(R.id.files_saf_grant);
        safCrumb = v.findViewById(R.id.files_saf_crumb);
        safFolder = v.findViewById(R.id.files_saf_folder);
        safCount = v.findViewById(R.id.files_saf_count);
        v.findViewById(R.id.files_choose_folder).setOnClickListener(x -> pickFolder());
        v.findViewById(R.id.files_saf_up).setOnClickListener(x -> safUp());

        // 默认停在「工作区」
        switchTo(MODE_WS);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mode == MODE_SAF) loadSaf();
    }

    private void switchTo(int m) {
        mode = m;
        wsToolbar.setVisibility(m == MODE_WS ? View.VISIBLE : View.GONE);
        wsCrumb.setVisibility(m == MODE_WS ? View.VISIBLE : View.GONE);
        wsSearch.setVisibility(m == MODE_WS ? View.VISIBLE : View.GONE);
        safGrant.setVisibility(m == MODE_SAF ? View.VISIBLE : View.GONE);
        safCrumb.setVisibility(m == MODE_SAF ? View.VISIBLE : View.GONE);
        setTabStyle();
        if (m == MODE_WS) {
            wsCurrent = null; // 回到工作区根
            loadWs();
        } else {
            loadSaf();
        }
    }

    private void setTabStyle() {
        boolean wsActive = (mode == MODE_WS);
        int on = requireContext().getResources().getColor(R.color.seed_blue);
        int off = requireContext().getResources().getColor(R.color.on_surface_variant);
        ((TextView) tabWs).setTextColor(wsActive ? on : off);
        ((TextView) tabSaf).setTextColor(!wsActive ? on : off);
    }

    // ============ 工作区 ============

    private void loadWs() {
        final android.content.Context ctx = requireContext();
        final Activity act = requireActivity();
        final String shownDir = wsCurrent == null ? fileTools.getWorkspace() : wsCurrent;
        String q = ((EditText) wsSearch).getText().toString().trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = new ArrayList<Row>();
                if (q.length() > 0) {
                    // 搜索模式：命中文件直接列出
                    for (String p : fileTools.searchName(fileTools.getWorkspace(), q)) {
                        File f = new File(p);
                        rows.add(wsRow(f, p));
                    }
                } else {
                    List<FileTools.FileEntry> entries = fileTools.listEntries(shownDir);
                    for (FileTools.FileEntry e : entries) rows.add(wsRow(new File(e.absPath), e.absPath));
                }
                final int dirCount = rows.size();
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        updateWsCrumb(shownDir, rows, q);
                        adapter.update(rows);
                        showEmpty(rows.isEmpty(), q.length() > 0 ? "无匹配" : "（空目录）");
                    }
                });
            }
        }).start();
    }

    private Row wsRow(File f, String absPath) {
        Row r = new Row();
        r.name = f.getName();
        r.isDir = f.isDirectory();
        r.absPath = absPath;
        r.fromSaf = false;
        if (r.isDir) {
            r.meta = "文件夹";
        } else {
            r.meta = fmtSize(f.length()) + " · "
                    + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date(f.lastModified()));
        }
        r.iconEmoji();
        return r;
    }

    private void updateWsCrumb(String shownDir, List<Row> rows, String q) {
        boolean atRoot = shownDir.equals(fileTools.getWorkspace());
        wsFolder.setText((atRoot ? "工作区" : new File(shownDir).getName()) + (q.length() > 0 ? " · 搜索「" + q + "」" : ""));
        int dirs = 0;
        long total = 0;
        for (Row r : rows) {
            if (r.isDir) dirs++;
            else if (!r.fromSaf && r.absPath != null) total += new File(r.absPath).length();
        }
        wsCount.setText(dirs + " 目录 · " + (rows.size() - dirs) + " 文件");
    }

    private void wsUp() {
        String cur = wsCurrent == null ? fileTools.getWorkspace() : wsCurrent;
        File parent = new File(cur).getParentFile();
        if (parent == null || cur.equals(fileTools.getWorkspace())) {
            wsCurrent = null;
        } else {
            wsCurrent = parent.getAbsolutePath();
        }
        loadWs();
    }

    private void wsNewFile() {
        promptText("新建文件（名称，可带 .txt 等扩展名）", "note.txt", new TextCb() {
            @Override public void onResult(String v) {
                String parent = wsCurrent == null ? fileTools.getWorkspace() : wsCurrent;
                String target = parent + File.separator + v;
                fileTools.write(target, "");
                openEditor(target);
                loadWs();
            }
        });
    }

    private void wsNewDir() {
        promptText("新建目录（名称）", "new_folder", new TextCb() {
            @Override public void onResult(String v) {
                String parent = wsCurrent == null ? fileTools.getWorkspace() : wsCurrent;
                String msg = fileTools.mkdir(parent + File.separator + v);
                toast(msg);
                loadWs();
            }
        });
    }

    // ============ 我的目录(SAF) ============

    private void loadSaf() {
        boolean granted = StorageAccess.isGranted(requireContext());
        safGrant.setVisibility(granted ? View.GONE : View.VISIBLE);
        safCrumb.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            adapter.update(new ArrayList<Row>());
            showEmpty(false, "");
            return;
        }
        final android.content.Context ctx = requireContext();
        final Activity act = requireActivity();
        String parent = safPath.isEmpty()
                ? StorageAccess.rootDocumentId(ctx)
                : safPath.get(safPath.size() - 1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<StorageAccess.Entry> data = StorageAccess.listChildren(ctx, parent);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        safItems = data;
                        updateSafCrumb();
                        applySafFilter("");
                    }
                });
            }
        }).start();
    }

    private void updateSafCrumb() {
        String root = StorageAccess.rootDisplayName(requireContext());
        safFolder.setText(safPath.isEmpty() ? root
                : root + " / " + safPathNames.get(safPathNames.size() - 1));
        int dirs = 0;
        for (StorageAccess.Entry e : safItems) if (e.isDir) dirs++;
        safCount.setText(dirs + " 目录 · " + (safItems.size() - dirs) + " 文件");
    }

    private void applySafFilter(String query) {
        List<Row> rows = new ArrayList<Row>();
        for (StorageAccess.Entry e : safItems) {
            if (!query.isEmpty() && !e.name.toLowerCase().contains(query.toLowerCase())) continue;
            Row r = new Row();
            r.name = e.name;
            r.isDir = e.isDir;
            r.fromSaf = true;
            r.docId = e.docId;
            r.mime = e.mime;
            r.meta = e.isDir ? "文件夹" : (fmtSize(e.size) + " · " + e.name);
            r.iconEmoji();
            rows.add(r);
        }
        adapter.update(rows);
        showEmpty(rows.isEmpty(), "（空目录）");
    }

    private void safUp() {
        if (!safPath.isEmpty()) {
            safPath.remove(safPath.size() - 1);
            safPathNames.remove(safPathNames.size() - 1);
        }
        loadSaf();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, RC_PICK_FOLDER);
    }

    @Override
    public void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req != RC_PICK_FOLDER || res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        StorageAccess.grant(requireContext(), data.getData());
        safPath.clear();
        safPathNames.clear();
        loadSaf();
    }

    // ============ 交互：进目录 / 编辑 / 操作菜单 ============

    private void onRowClick(Row r) {
        if (r.isDir) {
            if (r.fromSaf) {
                safPath.add(r.docId);
                safPathNames.add(r.name);
                loadSaf();
            } else {
                wsCurrent = r.absPath;
                loadWs();
            }
            return;
        }
        // 文件：文本进编辑器，非文本进系统/发给智能体
        if (r.fromSaf) {
            if (isSafText(r)) openEditorSaf(r);
            else sendSafToAgent(r);
        } else {
            if (isTextLike(r.name)) openEditor(r.absPath);
            else openInSystem(r.absPath, guessMime(r.name));
        }
    }

    // 操作菜单：按来源/目录/文件生成不同动作
    private void onActions(Row r) {
        List<String> items = new ArrayList<String>();
        if (r.fromSaf) {
            if (isSafText(r)) items.add("编辑");
            items.add("发给智能体");
            items.add("用系统打开");
            items.add("分享");
        } else {
            if (r.isDir) {
                items.add("移动 / 重命名");
                items.add("压缩为 zip");
                items.add("删除");
            } else {
                items.add("编辑");
                items.add("复制");
                items.add("移动 / 重命名");
                if (r.name.toLowerCase().endsWith(".zip")) items.add("解压");
                items.add("压缩为 zip");
                items.add("发给智能体");
                items.add("用系统打开");
                items.add("分享");
                items.add("删除");
            }
        }
        if (items.isEmpty()) return;
        final String[] act = items.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle(r.name)
                .setItems(act, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        doAction(r, act[which]);
                    }
                })
                .show();
    }

    private void doAction(Row r, String a) {
        if (r.fromSaf) {
            if (a.equals("编辑")) openEditorSaf(r);
            else if (a.equals("发给智能体")) sendSafToAgent(r);
            else if (a.equals("用系统打开")) openSafInSystem(r);
            else if (a.equals("分享")) shareSaf(r);
            return;
        }
        String parentDir = new File(r.absPath).getParent();
        switch (a) {
            case "编辑":
                openEditor(r.absPath);
                break;
            case "复制":
                promptText("复制为（新名称，同目录）", r.name + " (copy)", new TextCb() {
                    @Override public void onResult(String v) {
                        toast(fileTools.copy(r.absPath, parentDir + File.separator + v));
                        loadWs();
                    }
                });
                break;
            case "移动 / 重命名":
                promptText("重命名为（目标目录内名称）", r.name, new TextCb() {
                    @Override public void onResult(String v) {
                        toast(fileTools.move(r.absPath, parentDir + File.separator + v));
                        loadWs();
                    }
                });
                break;
            case "解压":
                promptText("解压到（工作区内目录名）", r.name.replace(".zip", ""), new TextCb() {
                    @Override public void onResult(String v) {
                        String dest = fileTools.getWorkspace() + File.separator + v;
                        fileTools.mkdir(dest);
                        toast(fileTools.unzip(r.absPath, dest));
                        loadWs();
                    }
                });
                break;
            case "压缩为 zip":
                promptText("压缩为 zip（输出文件名，存到工作区根）",
                        new File(r.absPath).getName() + ".zip", new TextCb() {
                    @Override public void onResult(String v) {
                        toast(fileTools.zip(r.absPath, fileTools.getWorkspace() + File.separator + v));
                        loadWs();
                    }
                });
                break;
            case "发给智能体":
                sendWsToAgent(r);
                break;
            case "用系统打开":
                openInSystem(r.absPath, guessMime(r.name));
                break;
            case "分享":
                share(r.absPath, r.name, guessMime(r.name));
                break;
            case "删除":
                new AlertDialog.Builder(requireContext())
                        .setTitle("删除「" + r.name + "」？")
                        .setMessage(r.isDir ? "将删除整个目录及其内容，不可恢复。" : "不可恢复。")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                toast(fileTools.delete(r.absPath));
                                loadWs();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                break;
        }
    }

    // ---- 编辑器 ----

    private void openEditor(String absPath) {
        Intent i = new Intent(requireContext(), FileEditorActivity.class);
        i.putExtra(FileEditorActivity.EXTRA_PATH, absPath);
        i.putExtra(FileEditorActivity.EXTRA_NAME, new File(absPath).getName());
        startActivity(i);
    }

    private void openEditorSaf(Row r) {
        Intent i = new Intent(requireContext(), FileEditorActivity.class);
        i.putExtra(FileEditorActivity.EXTRA_DOC_ID, r.docId);
        i.putExtra(FileEditorActivity.EXTRA_NAME, r.name);
        startActivity(i);
    }

    // ---- 发给智能体 ----

    private void sendWsToAgent(Row r) {
        String sid = AgentBackend.get().createSession("文件：" + r.name, null);
        AgentBackend.get().runTask(sid, "请读取并处理工作区里的这个文件：" + r.absPath, null);
        toast("已发给智能体（工作区文件，可直接读取）");
    }

    private void sendSafToAgent(Row r) {
        Models.Attachment a = StorageAccess.sendToAgent(requireContext(), r.docId, r.name);
        if (a == null) {
            toast("拷贝失败");
            return;
        }
        List<Models.Attachment> atts = new ArrayList<Models.Attachment>();
        atts.add(a);
        String sid = AgentBackend.get().createSession("文件：" + r.name, null);
        AgentBackend.get().runTask(sid, "请读取并处理这个文件：", atts);
        toast("已发给智能体");
    }

    // ---- 分享 / 系统打开 ----

    private void openInSystem(String absPath, String mime) {
        File f = new File(absPath);
        if (!f.exists()) {
            toast("文件不存在");
            return;
        }
        Uri uri = FileProvider.getUriForFile(requireContext(), PROVIDER_AUTHORITY, f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime == null ? "*/*" : mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("没有能打开「" + f.getName() + "」的应用");
        }
    }

    private void openSafInSystem(Row r) {
        Uri u = StorageAccess.docUri(requireContext(), r.docId);
        if (u == null) {
            toast("无法获取文件地址");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, r.mime == null ? "*/*" : r.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("没有能打开「" + r.name + "」的应用");
        }
    }

    private void share(String absPath, String name, String mime) {
        Uri uri = FileProvider.getUriForFile(requireContext(), PROVIDER_AUTHORITY, new File(absPath));
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime == null ? "*/*" : mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "分享 " + name));
    }

    private void shareSaf(Row r) {
        Uri u = StorageAccess.docUri(requireContext(), r.docId);
        if (u == null) {
            toast("无法获取文件地址");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(r.mime == null ? "*/*" : r.mime);
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "分享 " + r.name));
    }

    // ---- 文本判断 ----

    private boolean isTextLike(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") || n.endsWith(".json")
                || n.endsWith(".xml") || n.endsWith(".csv") || n.endsWith(".java") || n.endsWith(".kt")
                || n.endsWith(".ts") || n.endsWith(".js") || n.endsWith(".py") || n.endsWith(".html")
                || n.endsWith(".css") || n.endsWith(".properties") || n.endsWith(".gradle")
                || n.endsWith(".toml") || n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".ini");
    }

    private boolean isSafText(Row r) {
        if (r.fromSaf) {
            // SAF 有 mime
            StorageAccess.Entry e = findSafEntry(r.docId);
            if (e != null) {
                if (e.mime != null && e.mime.startsWith("text/")) return true;
                String n = e.name.toLowerCase();
                if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")
                        || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".csv")) return true;
            }
        }
        return isTextLike(r.name);
    }

    private StorageAccess.Entry findSafEntry(String docId) {
        for (StorageAccess.Entry e : safItems) if (e.docId != null && e.docId.equals(docId)) return e;
        return null;
    }

    private String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    // ---- 工具 ----

    private void showEmpty(boolean show, String text) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        recycler.setVisibility(show ? View.GONE : View.VISIBLE);
        empty.setText(text);
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    // 文本输入框
    private void promptText(String title, String def, final TextCb cb) {
        final EditText et = new EditText(requireContext());
        et.setText(def);
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (!v.isEmpty()) cb.onResult(v);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    interface TextCb {
        void onResult(String v);
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    // ============ 行适配器 ============

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final List<Row> items;

        RowAdapter(List<Row> items) {
            this.items = items;
        }

        void update(List<Row> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final VH h, int pos) {
            final Row r = items.get(pos);
            h.name.setText(r.name);
            h.meta.setText(r.meta);
            h.icon.setText(r.icon);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onRowClick(r);
                }
            });
            // 操作菜单：目录和文件都有；SAF 目录暂不需要（点进去即可）
            boolean showActions = !r.fromSaf || !r.isDir;
            h.actions.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.actions.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onActions(r);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name, meta, icon;
            final com.google.android.material.button.MaterialButton actions;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.file_name);
                meta = v.findViewById(R.id.file_meta);
                icon = v.findViewById(R.id.file_icon);
                actions = v.findViewById(R.id.file_actions);
            }
        }
    }
}
