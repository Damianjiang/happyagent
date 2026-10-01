package com.happyagent.mobile.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.StorageAccess;
import com.happyagent.mobile.model.Models;

import java.util.ArrayList;
import java.util.List;

// 内置文件浏览器：SAF 授权一个文件夹后，可浏览 / 编辑 / 把文件发给智能体。
// "选文件夹"走系统文档选择器（权限自动申请），授权只覆盖该目录，安全。
public class FilesFragment extends Fragment {

    private static final int RC_PICK_FOLDER = 1;

    private View grantCard, crumb;
    private TextView empty;
    private RecyclerView recycler;
    private FileAdapter adapter;
    // 当前目录 docId 栈：栈顶 = 当前正在看的目录，空 = 授权根
    private final List<String> path = new ArrayList<String>();
    // 与 path 平行的目录显示名栈（面包屑可读）
    private final List<String> pathNames = new ArrayList<String>();
    private String currentParent;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_files, container, false);
        grantCard = v.findViewById(R.id.files_grant);
        crumb = v.findViewById(R.id.files_breadcrumb);
        empty = v.findViewById(R.id.files_empty);
        recycler = v.findViewById(R.id.files_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter(new ArrayList<StorageAccess.Entry>());
        recycler.setAdapter(adapter);

        v.findViewById(R.id.files_choose_folder).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        pickFolder();
                    }
                });
        v.findViewById(R.id.files_up).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goUp();
            }
        });

        refresh();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // 从编辑器/选文件夹回来重刷
    }

    // 已授权就进浏览器，没授权就显示"选文件夹"引导
    private void refresh() {
        boolean granted = StorageAccess.isGranted(requireContext());
        grantCard.setVisibility(granted ? View.GONE : View.VISIBLE);
        crumb.setVisibility(granted ? View.VISIBLE : View.GONE);
        if (!granted) return;
        enterRoot();
    }

    private void enterRoot() {
        path.clear();
        pathNames.clear();
        currentParent = StorageAccess.rootDocumentId(requireContext());
        loadDir();
    }

    // 上翻：弹掉栈顶；空栈回到授权根
    private void goUp() {
        if (!path.isEmpty()) {
            path.remove(path.size() - 1);
            pathNames.remove(pathNames.size() - 1);
        }
        currentParent = path.isEmpty()
                ? StorageAccess.rootDocumentId(requireContext())
                : path.get(path.size() - 1);
        loadDir();
    }

    private void loadDir() {
        final String parent = currentParent;
        final android.content.Context ctx = requireContext();
        final android.app.Activity act = requireActivity();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<StorageAccess.Entry> data = StorageAccess.listChildren(ctx, parent);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        adapter.update(data);
                        boolean isEmpty = data.isEmpty();
                        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                        recycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                        updateCrumb();
                    }
                });
            }
        }).start();
    }

    private void updateCrumb() {
        TextView name = crumb.findViewById(R.id.files_folder_name);
        if (path.isEmpty()) {
            name.setText(StorageAccess.rootDisplayName(requireContext()));
        } else {
            name.setText(StorageAccess.rootDisplayName(requireContext())
                    + " / " + pathNames.get(pathNames.size() - 1));
        }
    }

    // 选文件夹 = 系统文档选择器；授权范围仅该目录，权限自动申请
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
        if (req != RC_PICK_FOLDER || res != android.app.Activity.RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        StorageAccess.grant(requireContext(), tree);
        refresh();
    }

    private void openEntry(StorageAccess.Entry e) {
        if (e.isDir) {
            path.add(e.docId);
            pathNames.add(e.name);
            currentParent = e.docId;
            loadDir();
            return;
        }
        if (isText(e)) openEditor(e);
        else sendToAgent(e);
    }

    private void openEditor(StorageAccess.Entry e) {
        Intent i = new Intent(requireContext(), FileEditorActivity.class);
        i.putExtra(FileEditorActivity.EXTRA_DOC_ID, e.docId);
        i.putExtra(FileEditorActivity.EXTRA_NAME, e.name);
        startActivity(i);
    }

    // 文本类才进编辑器；其余（图片/压缩包等）只能发给智能体
    private boolean isText(StorageAccess.Entry e) {
        String m = e.mime == null ? "" : e.mime;
        if (m.startsWith("text/")) return true;
        String n = e.name.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")
                || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".csv");
    }

    // 把文件安全拷进 agent 工作区附件目录，开一个会话让它读
    private void sendToAgent(StorageAccess.Entry e) {
        Models.Attachment a = StorageAccess.sendToAgent(requireContext(), e.docId, e.name);
        if (a == null) {
            android.widget.Toast.makeText(requireContext(), "拷贝失败",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<Models.Attachment> atts = new ArrayList<Models.Attachment>();
        atts.add(a);
        String sid = AgentBackend.get().createSession("文件：" + e.name, null);
        AgentBackend.get().runTask(sid, "请读取并处理这个文件：", atts);
        android.widget.Toast.makeText(requireContext(), "已发给智能体",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private final List<StorageAccess.Entry> items;

        FileAdapter(List<StorageAccess.Entry> items) {
            this.items = items;
        }

        void update(List<StorageAccess.Entry> data) {
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
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final StorageAccess.Entry e = items.get(pos);
            h.name.setText(e.name);
            h.meta.setText(e.isDir ? "文件夹" : (fmtSize(e.size) + " · " + e.name));
            h.icon.setText(e.isDir ? "📁" : "📄");
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FilesFragment.this.openEntry(e);
                }
            });
            h.sendBtn.setVisibility(e.isDir ? View.GONE : View.VISIBLE);
            h.sendBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FilesFragment.this.sendToAgent(e);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static String fmtSize(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
            return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name, meta, icon;
            final com.google.android.material.button.MaterialButton sendBtn;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.file_name);
                meta = v.findViewById(R.id.file_meta);
                icon = v.findViewById(R.id.file_icon);
                sendBtn = v.findViewById(R.id.file_send);
            }
        }
    }
}
