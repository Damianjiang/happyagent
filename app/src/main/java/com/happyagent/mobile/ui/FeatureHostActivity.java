package com.happyagent.mobile.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.happyagent.mobile.R;

// 通用功能宿主页：从"设置"功能列表进入 工具 / 文件 等原底部功能页，带返回箭头。
// intent extra EXTRA_FRAGMENT = "tools" / "files"。
public class FeatureHostActivity extends AppCompatActivity {

    public static final String EXTRA_FRAGMENT = "fragment";
    public static final String EXTRA_TITLE = "title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_feature_host);
        MaterialToolbar t = (MaterialToolbar) findViewById(R.id.host_toolbar);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        t.setTitle(title == null ? "功能" : title);
        t.setNavigationOnClickListener(v -> finish());

        if (savedInstanceState == null
                || getSupportFragmentManager().findFragmentById(R.id.host_container) == null) {
            String which = getIntent().getStringExtra(EXTRA_FRAGMENT);
            androidx.fragment.app.Fragment f = "tools".equals(which)
                    ? new ToolsFragment() : new FilesFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.host_container, f)
                    .commit();
        }
    }
}
