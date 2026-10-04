package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;

// 设置子页基类：每个功能一个独立页（壳工具栏 + 各自内容布局），
// 子类只管 title / 内容布局 / 绑定，不再共用一张大杂烩页。
public abstract class SectionSettingsActivity extends AppCompatActivity {

    protected Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_section_shell);
        prefs = new Prefs(this);

        com.google.android.material.appbar.MaterialToolbar t =
                findViewById(R.id.sec_toolbar);
        t.setTitle(title());
        t.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.sec_container);
        getLayoutInflater().inflate(contentRes(), container, true);
        bind();
    }

    protected abstract String title();

    protected abstract int contentRes();

    protected abstract void bind();

    // 取强调色/语义色：走 ContextCompat，自动跟随深浅色资源限定符
    protected int getThemeColor(int resId) {
        return androidx.core.content.ContextCompat.getColor(this, resId);
    }
}
