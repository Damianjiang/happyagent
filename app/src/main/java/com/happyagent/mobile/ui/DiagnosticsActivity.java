package com.happyagent.mobile.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.happyagent.mobile.R;

// 系统诊断独立页：承载 ReportsFragment（会话/工具/运行时/模型接入/个性化/能力/Web 状态快照）。
// 从"设置"功能列表页点"系统诊断"进入。
public class DiagnosticsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_diagnostics);
        MaterialToolbar t = (MaterialToolbar) findViewById(R.id.diag_toolbar);
        t.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null
                || getSupportFragmentManager().findFragmentById(R.id.diag_container) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.diag_container, new ReportsFragment())
                    .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回来刷新诊断数据
        androidx.fragment.app.Fragment f =
                getSupportFragmentManager().findFragmentById(R.id.diag_container);
        if (f instanceof ReportsFragment) ((ReportsFragment) f).load();
    }
}
