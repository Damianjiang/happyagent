package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.StorageAccess;
import com.happyagent.mobile.tools.FileTools;

// 文本编辑器：两种来源——工作区文件（绝对路径，java.io 直读直写）与 SAF 文件（docId，经 ContentResolver）。
// 工作区文件支持新建：路径不存在时保存即创建。
// 读写全放后台线程，老安卓 6 上主线程不卡。
public class FileEditorActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_ID = "doc_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PATH = "path";

    private EditText editor;
    private TextView status;
    private ProgressBar loading;
    private String docId;     // SAF 来源
    private String path;      // 工作区来源
    private String name;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_file_editor);

        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        path = getIntent().getStringExtra(EXTRA_PATH);
        name = getIntent().getStringExtra(EXTRA_NAME);

        // 两个来源至少有一个，否则无处可编辑
        if (docId == null && path == null) {
            finish();
            return;
        }

        MaterialToolbar tb = findViewById(R.id.ed_toolbar);
        tb.setTitle("编辑 " + (name == null ? "" : name));
        tb.setNavigationOnClickListener(v -> finish());

        editor = findViewById(R.id.ed_content);
        status = findViewById(R.id.ed_status);
        loading = findViewById(R.id.ed_loading);

        final boolean isNew = path != null && !new java.io.File(path).exists();
        status.setText(isNew ? "新文件（保存后创建）" : "加载中…");

        findViewById(R.id.ed_save).setOnClickListener(v -> save());

        // 后台读文件，不卡主线程
        loadInBackground();
    }

    private void loadInBackground() {
        final String p = path;
        final String d = docId;
        loading.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String content;
                try {
                    if (p != null) {
                        FileTools ft = new FileTools(getApplicationContext());
                        content = ft.readContent(p);
                    } else {
                        content = StorageAccess.readText(getApplicationContext(), d);
                    }
                } catch (Exception e) {
                    content = "";
                }
                final String result = content == null ? "" : content;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.setVisibility(View.GONE);
                        editor.setText(result);
                        loaded = true;
                        if (p != null && !new java.io.File(p).exists()) {
                            status.setText("新文件（保存后创建）");
                        } else {
                            status.setText("");
                        }
                    }
                });
            }
        }, "file-load").start();
    }

    private void save() {
        if (!loaded) {
            Toast.makeText(this, "文件还在加载，稍等…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String content = editor.getText().toString();
        loading.setVisibility(View.VISIBLE);
        status.setText("保存中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    if (path != null) {
                        FileTools ft = new FileTools(getApplicationContext());
                        msg = ft.write(path, content);
                    } else {
                        msg = StorageAccess.writeText(getApplicationContext(), docId, content);
                    }
                } catch (Exception e) {
                    msg = "保存失败：" + e.getMessage();
                }
                final String result = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.setVisibility(View.GONE);
                        status.setText(result);
                        Toast.makeText(FileEditorActivity.this, result, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "file-save").start();
    }
}
