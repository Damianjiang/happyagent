package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.switchmaterial.SwitchMaterial;

// 设置 / 个性化：深浅色主题、震动、摇一摇记日志、起始页、Web 服务开关；都持久化下次用
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        // 深浅色，切换当场 recreate 套新主题
        SwitchMaterial night = findViewById(R.id.set_night);
        night.setChecked(prefs.getInt(Prefs.KEY_NIGHT_MODE, Prefs.NIGHT_FOLLOW_SYSTEM) == Prefs.NIGHT_YES);
        night.setOnCheckedChangeListener((b, isOn) -> {
            int mode = isOn ? Prefs.NIGHT_YES : Prefs.NIGHT_NO;
            prefs.putInt(Prefs.KEY_NIGHT_MODE, mode);
            AppCompatDelegate.setDefaultNightMode(mode);
            recreate();
        });

        // 震动反馈：开着的点按钮才响（Haptics 只在开关开时执行）
        SwitchMaterial haptics = findViewById(R.id.set_haptics);
        haptics.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_HAPTICS, true));
        haptics.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_HAPTICS, isOn));

        // 摇一摇抓一份日志（MainActivity 挂传感器，开关决定要不要注册）
        SwitchMaterial shake = findViewById(R.id.set_shake_log);
        shake.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, false));
        shake.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, isOn));

        // 启动停在哪个页：0 会话 / 1 工具
        SwitchMaterial startTools = findViewById(R.id.set_start_tools);
        startTools.setChecked(prefs.getInt(Prefs.KEY_START_PAGE, 0) == 1);
        startTools.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putInt(Prefs.KEY_START_PAGE, isOn ? 1 : 0);
            Toast.makeText(this, "起始页已更新", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.set_back).setOnClickListener(v -> finish());

        // 版本卡点开关于
        findViewById(R.id.set_about_card).setOnClickListener(v -> AboutDialog.show(this));

        // Web 服务开关：开即拉前台服务并记住偏好，关即停并清偏好。
        // start/stop 是异步的，用开关本身状态为准显示/隐藏 IP，避免竞态
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

    // Web 服务开关的当前意图（异步 start/stop 期间以它为准）
    private boolean webuiOn;
    private int webuiPollCount;
    private final android.os.Handler webuiPoll = new android.os.Handler();
    private Runnable webuiPollRunnable;

    // 按开关意图显示内网/外网 IP:端口；内网服务起来才有，外网后台 8s 取到，两者都拿到即停轮询
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
                // 内网拿到后不再等外网；外网拿不到也别无限刷，10 次后停
                if (lan.isEmpty() || (webuiPollCount < 10 && pub.isEmpty())) {
                    webuiPoll.postDelayed(this, 1500);
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

    // 在本机浏览器打开 Web 端；本机连接用 127.0.0.1（内网地址还没取到时）
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
