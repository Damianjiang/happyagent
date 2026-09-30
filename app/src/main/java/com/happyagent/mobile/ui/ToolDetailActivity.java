package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Tool;
import com.google.android.material.switchmaterial.SwitchMaterial;

// 工具详情：完整说明 + 启停，和原版的插件详情一个用法
public class ToolDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TOOL_ID = "tool_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_detail);

        String id = getIntent().getStringExtra(EXTRA_TOOL_ID);
        Tool t = AgentBackend.get().getTool(id);
        if (t == null) {
            finish();
            return;
        }

        TextView name = findViewById(R.id.toold_name);
        TextView desc = findViewById(R.id.toold_desc);
        TextView category = findViewById(R.id.toold_category);
        SwitchMaterial sw = findViewById(R.id.toold_switch);

        name.setText(t.name);
        desc.setText(t.desc);
        category.setText(t.category);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(t.enabled);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AgentBackend.get().setToolEnabled(t.id, isChecked);
            Toast.makeText(ToolDetailActivity.this,
                    t.name + (isChecked ? " 已启用" : " 已停用"), Toast.LENGTH_SHORT).show();
        });

        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.toold_toolbar);
        if (toolbar != null) {
            toolbar.setTitle(t.name);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
        findViewById(R.id.toold_close).setOnClickListener(v -> finish());
    }
}
