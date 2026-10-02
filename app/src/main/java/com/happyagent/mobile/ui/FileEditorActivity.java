package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.StorageAccess;
import com.happyagent.mobile.tools.FileTools;

// 文本编辑器：两种来源——工作区文件（绝对路径，java.io 直读直写）与 SAF 文件（docId，经 ContentResolver）。
// 工作区文件支持"新建"（路径不存在时保存即创建），编辑器不再是打不开的入口。
public class FileEditorActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_ID = "doc_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PATH = "path";

    private EditText editor;
    private TextView status;
    private String docId;     // SAF 来源
    private String path;      // 工作区来源
    private String name;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        final boolean isNew = path != null && !new java.io.File(path).exists();
        status.setText(isNew ? "新文件（保存后创建）" : "");

        findViewById(R.id.ed_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });

        editor.setText(load());
        editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) status.setText("");
            }
        });
    }

    private String load() {
        if (path != null) {
            FileTools ft = new FileTools(getApplicationContext());
            return ft.readContent(path);
        }
        return StorageAccess.readText(getApplicationContext(), docId);
    }

    private void save() {
        String content = editor.getText().toString();
        String msg;
        if (path != null) {
            FileTools ft = new FileTools(getApplicationContext());
            msg = ft.write(path, content);
        } else {
            msg = StorageAccess.writeText(getApplicationContext(), docId, content);
        }
        status.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
