package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.switchmaterial.SwitchMaterial;

// Web 服务：开关常驻服务 + 内外网地址轮询 + 浏览器打开
public class WebSettingsActivity extends SectionSettingsActivity {

    private boolean webuiOn;
    private int webuiPollCount;
    private final android.os.Handler webuiPoll = new android.os.Handler();
    private Runnable webuiPollRunnable;

    @Override
    protected String title() {
        return "Web 服务";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_web;
    }

    @Override
    protected void bind() {
        View webuiInfo = findViewById(R.id.webui_info);
        SwitchMaterial webui = findViewById(R.id.set_webui);
        webuiOn = com.happyagent.mobile.service.WebUiService.isRunning();
        webui.setChecked(webuiOn);
        webui.setOnCheckedChangeListener((b, isOn) -> {
            if (isOn) {
                prefs.putBoolean(Prefs.KEY_WEBUI_ON, true);
                com.happyagent.mobile.service.WebUiService.start(this);
            } else {
                prefs.putBoolean(Prefs.KEY_WEBUI_ON, false);
                com.happyagent.mobile.service.WebUiService.stop(this);
            }
            webuiOn = isOn;
            updateWebuiInfo(webuiInfo);
        });
        updateWebuiInfo(webuiInfo);
    }

    private void updateWebuiInfo(View webuiInfo) {
        if (!webuiOn) {
            webuiInfo.setVisibility(View.GONE);
            stopWebuiPoll();
            return;
        }
        webuiInfo.setVisibility(View.VISIBLE);
        int port = com.happyagent.mobile.service.WebUiService.port();
        String lan = com.happyagent.mobile.service.WebUiService.lanIpStatic();
        String pub = com.happyagent.mobile.service.WebUiService.publicIpStatic();
        TextView lanTv = findViewById(R.id.webui_lan);
        TextView pubTv = findViewById(R.id.webui_public);
        lanTv.setText((lan.isEmpty() ? "（获取中）" : lan) + ":" + port);
        pubTv.setText(pub.isEmpty() ? "（获取中）" : pub + ":" + port);
        final String lanForOpen = lan;
        findViewById(R.id.webui_open).setOnClickListener(v -> openInBrowser(lanForOpen, port));
        startWebuiPoll(lanTv, pubTv);
    }

    private void startWebuiPoll(TextView lanTv, TextView pubTv) {
        stopWebuiPoll();
        webuiPollCount = 0;
        webuiPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!webuiOn) return;
                webuiPollCount++;
                int port = com.happyagent.mobile.service.WebUiService.port();
                String lan = com.happyagent.mobile.service.WebUiService.lanIpStatic();
                String pub = com.happyagent.mobile.service.WebUiService.publicIpStatic();
                lanTv.setText((lan.isEmpty() ? "（获取中）" : lan) + ":" + port);
                pubTv.setText(pub.isEmpty() ? "（获取中）" : pub + ":" + port);
                if (lan.isEmpty() || (webuiPollCount < 10 && pub.isEmpty())) {
                    if (webuiPollCount < 30) {
                        webuiPoll.postDelayed(this, 1500);
                    }
                }
            }
        };
        webuiPoll.postDelayed(webuiPollRunnable, 1500);
    }

    private void stopWebuiPoll() {
        if (webuiPollRunnable != null) {
            webuiPoll.removeCallbacks(webuiPollRunnable);
            webuiPollRunnable = null;
        }
    }

    private void openInBrowser(String lan, int port) {
        String host = lan.isEmpty() ? "127.0.0.1" : lan;
        String url = "http://" + host + ":" + port;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器：" + url, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        stopWebuiPoll();
        super.onDestroy();
    }
}
