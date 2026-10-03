package com.happyagent.mobile.ui;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.happyagent.mobile.R;

// 模型接入独立设置页：承载 ConfigFragment（供应商/模型/温度/最大步数/拉取上游模型）。
// 从"设置"功能列表页点"模型接入"进入，底部返回箭头回到设置列表。
public class ModelConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_model_config);
        MaterialToolbar t = (MaterialToolbar) findViewById(R.id.mc_toolbar);
        t.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null
                || getSupportFragmentManager().findFragmentById(R.id.mc_container) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mc_container, new ConfigFragment())
                    .commit();
        }
    }
}
