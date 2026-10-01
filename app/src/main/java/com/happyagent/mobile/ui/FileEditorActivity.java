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

// 文本编辑器：编辑 SAF 授权目录里的文本文件，直接写回原文件
public class FileEditorActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_ID = "doc_id";
    public static final String EXTRA_NAME = "name";

    private EditText editor;
    private TextView status;
    private String docId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_editor);

        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        final String name = getIntent().getStringExtra(EXTRA_NAME);
        if (docId == null) {
            finish();
            return;
        }

        MaterialToolbar tb = findViewById(R.id.ed_toolbar);
        tb.setTitle("编辑 " + (name == null ? "" : name));
        tb.setNavigationOnClickListener(v -> finish());

        editor = findViewById(R.id.ed_content);
        status = findViewById(R.id.ed_status);
        findViewById(R.id.ed_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String msg = StorageAccess.writeText(getApplicationContext(), docId,
                        editor.getText().toString());
                status.setText(msg);
                Toast.makeText(FileEditorActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        // 内容读进编辑器（文件可能较大，直接读整份）
        editor.setText(StorageAccess.readText(getApplicationContext(), docId));
        editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) status.setText("");
            }
        });
    }
}
