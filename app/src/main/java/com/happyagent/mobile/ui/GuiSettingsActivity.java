package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.happyagent.mobile.R;

// GUI 自动化：无障碍服务状态 + 跳系统「无障碍」设置手动开启
public class GuiSettingsActivity extends SectionSettingsActivity {

    @Override
    protected String title() {
        return "GUI 自动化";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_gui;
    }

    @Override
    protected void bind() {
        findViewById(R.id.set_gui_open).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置里搜索「无障碍」手动开启", Toast.LENGTH_LONG).show();
            }
        });
        refreshGuiState(findViewById(R.id.set_gui_state));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGuiState(findViewById(R.id.set_gui_state));
    }

    // GUI 自动化（无障碍服务）是否已开启
    private void refreshGuiState(TextView stateTv) {
        boolean on = com.happyagent.mobile.service.GuardService.isRunning();
        stateTv.setText(on ? "● 无障碍服务已开启" : "○ 无障碍服务未开启");
        stateTv.setTextColor(on
                ? androidx.core.content.ContextCompat.getColor(this, R.color.status_done)
                : androidx.core.content.ContextCompat.getColor(this, R.color.status_failed));
    }
}
