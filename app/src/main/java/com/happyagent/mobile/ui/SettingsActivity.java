package com.happyagent.mobile.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import android.widget.Button;
import android.widget.ProgressBar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;

// 设置 / 个性化：主题三态、强调色 5 选 1、会话字号、震动/摇一摇/起始页、Web 开关、关于。
// 外观改动当场套主题重建；其余持久化下次用。颜色全走当前主题的色资源，强调色切换后整体跟走。
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private int currentAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        currentAccent = prefs.getInt(Prefs.KEY_ACCENT, Prefs.ACCENT_DEFAULT);

        com.google.android.material.appbar.MaterialToolbar t =
                (com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.set_toolbar);
        t.setNavigationOnClickListener(v -> finish());

        bindThemeButtons();
        bindAccentRow();
        bindSizeButtons();

        // 行为区
        SwitchMaterial haptics = findViewById(R.id.set_haptics);
        haptics.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_HAPTICS, true));
        haptics.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_HAPTICS, isOn));

        SwitchMaterial shake = findViewById(R.id.set_shake_log);
        shake.setChecked(prefs.getBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, false));
        shake.setOnCheckedChangeListener((b, isOn) -> prefs.putBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, isOn));

        SwitchMaterial startTools = findViewById(R.id.set_start_tools);
        startTools.setChecked(prefs.getInt(Prefs.KEY_START_PAGE, 0) == 1);
        startTools.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putInt(Prefs.KEY_START_PAGE, isOn ? 1 : 0);
            Toast.makeText(this, "起始页已更新", Toast.LENGTH_SHORT).show();
        });

        // 朗读回复：任务成功后 TTS 自动念 AI 最后一条
        SwitchMaterial tts = findViewById(R.id.set_tts);
        tts.setChecked(prefs.getBoolean(Prefs.KEY_TTS_ON, false));
        tts.setOnCheckedChangeListener((b, isOn) -> {
            prefs.putBoolean(Prefs.KEY_TTS_ON, isOn);
            if (isOn) com.happyagent.mobile.data.TtsEngine.get().speak("朗读已开启");
            else com.happyagent.mobile.data.TtsEngine.get().stop();
            Toast.makeText(this, isOn ? "朗读已开启" : "朗读已关闭", Toast.LENGTH_SHORT).show();
        });

        // 关于卡（版本号动态取包元信息，不写死）
        TextView versionTv = findViewById(R.id.set_about_version);
        versionTv.setText("Happy Agent " + AboutDialog.versionOf(this));
        findViewById(R.id.set_about_card).setOnClickListener(v -> AboutDialog.show(this));

        // 数据卡：显示授权目录、撤销授权、清除会话（数据层早能，界面补入口）
        TextView dataDir = findViewById(R.id.set_data_dir);
        String root = com.happyagent.mobile.data.StorageAccess.rootDisplayName(this);
        dataDir.setText(com.happyagent.mobile.data.StorageAccess.isGranted(this) ? root : "未授权");
        findViewById(R.id.set_data_revoke).setOnClickListener(v -> {
            new Prefs(this).putStringStorage("");
            dataDir.setText("未授权");
            Toast.makeText(this, "已撤销授权目录", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.set_data_clear_sessions).setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("清除全部会话？")
                    .setMessage("所有会话与消息将被删除，不可恢复。")
                    .setPositiveButton("清除", (d, w) -> {
                        com.happyagent.mobile.data.AgentBackend.get().clearSessions();
                        Toast.makeText(this, "已清除全部会话", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 能力扩展卡：GUI 自动化（无障碍服务）状态 + 角色卡编辑器
        TextView guiState = findViewById(R.id.set_gui_state);
        android.widget.Button guiOpen = findViewById(R.id.set_gui_open);
        guiOpen.setOnClickListener(v -> {
            // 跳到系统「无障碍」设置，让用户手动开启我们的 GuardService（诚实：不做静默自启）
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置里搜索「无障碍」手动开启", Toast.LENGTH_LONG).show();
            }
        });
        refreshGuiState(guiState);

        android.widget.EditText roleBox = findViewById(R.id.set_role_card);
        roleBox.setText(prefs.getString(Prefs.KEY_ROLE_CARD, ""));
        android.widget.EditText roleBoxName = findViewById(R.id.set_role_name);
        roleBoxName.setText(prefs.getString(Prefs.KEY_ROLE_NAME, ""));
        findViewById(R.id.set_role_save).setOnClickListener(v -> {
            String txt = roleBox.getText().toString().trim();
            String nm = roleBoxName.getText().toString().trim();
            prefs.putString(Prefs.KEY_ROLE_CARD, txt);
            prefs.putString(Prefs.KEY_ROLE_NAME, nm);
            boolean any = !txt.isEmpty() || !nm.isEmpty();
            Toast.makeText(this, any ? "角色卡已保存" : "已清除角色卡", Toast.LENGTH_SHORT).show();
        });
        // 预设风格：点一下把「角色名 + 角色设定」一起填进上面两项，可再改
        LinearLayout presetRow = findViewById(R.id.set_role_presets);
        fillRolePresets(presetRow, roleBoxName, roleBox);

        // 提示词编辑：自定义系统提示词，真注入引擎；加载已存值 + 状态 + 保存
        android.widget.EditText sysPromptBox = findViewById(R.id.set_system_prompt);
        android.widget.TextView sysPromptState = findViewById(R.id.set_system_prompt_state);
        String sysInit = prefs.getString(Prefs.KEY_SYSTEM_PROMPT, "").trim();
        sysPromptBox.setText(sysInit);
        sysPromptState.setText(sysInit.isEmpty() ? "未自定义（用内置默认）" : "已自定义");
        sysPromptState.setTextColor(sysInit.isEmpty()
                ? androidx.core.content.ContextCompat.getColor(this, R.color.status_failed)
                : androidx.core.content.ContextCompat.getColor(this, R.color.status_done));
        findViewById(R.id.set_system_prompt_save).setOnClickListener(v -> {
            String s = sysPromptBox.getText().toString().trim();
            prefs.putString(Prefs.KEY_SYSTEM_PROMPT, s);
            sysPromptState.setText(s.isEmpty() ? "未自定义（用内置默认）" : "已自定义");
            sysPromptState.setTextColor(s.isEmpty()
                    ? androidx.core.content.ContextCompat.getColor(this, R.color.status_failed)
                    : androidx.core.content.ContextCompat.getColor(this, R.color.status_done));
            Toast.makeText(this, s.isEmpty() ? "已恢复内置默认提示词" : "自定义提示词已保存",
                    Toast.LENGTH_SHORT).show();
        });

        // 容器环境（proot）：一键部署 + 进度 + 取消
        TextView prootState = findViewById(R.id.set_proot_state);
        ProgressBar prootProg = findViewById(R.id.set_proot_progress);
        TextView prootProgLabel = findViewById(R.id.set_proot_progress_label);
        Button prootDeploy = (Button) findViewById(R.id.set_proot_deploy);
        Button prootCancel = (Button) findViewById(R.id.set_proot_cancel);
        bindProot(prootState, prootProg, prootProgLabel, prootDeploy, prootCancel);

        // Web 服务开关
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

    // ============ 外观 ============

    private int themeMode() {
        return prefs.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW);
    }

    private void bindThemeButtons() {
        int mode = themeMode();
        segSet(findViewById(R.id.set_theme_follow), mode == Prefs.THEME_FOLLOW);
        segSet(findViewById(R.id.set_theme_light), mode == Prefs.THEME_LIGHT);
        segSet(findViewById(R.id.set_theme_dark), mode == Prefs.THEME_DARK);
        findViewById(R.id.set_theme_follow).setOnClickListener(v -> setThemeMode(Prefs.THEME_FOLLOW));
        findViewById(R.id.set_theme_light).setOnClickListener(v -> setThemeMode(Prefs.THEME_LIGHT));
        findViewById(R.id.set_theme_dark).setOnClickListener(v -> setThemeMode(Prefs.THEME_DARK));
    }
    private void setThemeMode(int m) {
        if (m == themeMode()) return;
        prefs.putInt(Prefs.KEY_THEME_MODE, m);
        AppCompatDelegate.setDefaultNightMode(ThemeUtil.nightModeOf(m));
        recreate();
    }

    private void bindAccentRow() {
        LinearLayout row = findViewById(R.id.set_accent_row);
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        int size = 30 * d;
        int gap = 14 * d;
        for (int i = 0; i < 5; i++) {
            row.addView(makeAccentDot(i, currentAccent, size, gap));
        }
    }

    private View makeAccentDot(int index, int current, int size, int gap) {
        int d = (int) getResources().getDisplayMetrics().density;
        // 外层容器负责布局与点击；选中时里层再叠一个实心小圆做出"外环"效果
        final LinearLayout dot = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = gap;
        dot.setLayoutParams(lp);
        dot.setGravity(android.view.Gravity.CENTER);

        int solid = resIdOfSolid(index);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (index == current) {
            // 选中：容器色做外环，中间留白，再放实心色点
            g.setColor(getThemeColor(resIdOfContainer(index)));
            dot.setBackground(g);
            View inner = new View(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    size - 6 * d, size - 6 * d);
            inner.setLayoutParams(ilp);
            GradientDrawable ig = new GradientDrawable();
            ig.setShape(GradientDrawable.OVAL);
            ig.setColor(getThemeColor(solid));
            inner.setBackground(ig);
            dot.addView(inner);
        } else {
            g.setColor(getThemeColor(solid));
            dot.setBackground(g);
        }
        dot.setOnClickListener(v -> {
            if (currentAccent == index) return;
            currentAccent = index;
            prefs.putInt(Prefs.KEY_ACCENT, index);
            recreate();   // 套新强调色 overlay 重建
        });
        return dot;
    }

    private int resIdOfSolid(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber;
            default:                 return R.color.acc_default;
        }
    }

    private int resIdOfContainer(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal_c;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss_c;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust_c;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber_c;
            default:                 return R.color.acc_default_c;
        }
    }

    private void bindSizeButtons() {
        int cur = prefs.getInt(Prefs.KEY_CHAT_TEXT_SIZE, 0);
        segSet(findViewById(R.id.set_size_s), cur == 0);
        segSet(findViewById(R.id.set_size_m), cur == 1);
        segSet(findViewById(R.id.set_size_l), cur == 2);
        findViewById(R.id.set_size_s).setOnClickListener(v -> setChatSize(0));
        findViewById(R.id.set_size_m).setOnClickListener(v -> setChatSize(1));
        findViewById(R.id.set_size_l).setOnClickListener(v -> setChatSize(2));
    }

    private void setChatSize(int v) {
        if (prefs.getInt(Prefs.KEY_CHAT_TEXT_SIZE, 0) == v) return;
        prefs.putInt(Prefs.KEY_CHAT_TEXT_SIZE, v);
        bindSizeButtons();
        Toast.makeText(this, "会话字号已更新", Toast.LENGTH_SHORT).show();
    }

    // 分段按钮选中态：选中=强调色容器底+容器文字，未选=透明底+发丝描边+次级文字
    // 背景 drawable 全走 theme attr，强调色切换后自动跟走，无需重建
    private void segSet(View v, boolean selected) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        b.setBackgroundResource(selected ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        b.setTextColor(selected ? getThemeColor(resIdOfOn(currentAccent))
                : getThemeColor(R.color.on_surface_variant));
    }

    private int resIdOfOn(int i) {
        switch (i) {
            case Prefs.ACCENT_TEAL:  return R.color.acc_teal_c_on;
            case Prefs.ACCENT_MOSS:  return R.color.acc_moss_c_on;
            case Prefs.ACCENT_RUST:  return R.color.acc_rust_c_on;
            case Prefs.ACCENT_AMBER: return R.color.acc_amber_c_on;
            default:                 return R.color.acc_default_c_on;
        }
    }

    // 角色卡预设风格：点一下把「角色名 + 角色设定」填进上面两项，可再改后保存
    private void fillRolePresets(LinearLayout row, final android.widget.EditText nameBox,
                                 final android.widget.EditText cardBox) {
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        String[][] presets = {
            {"小助", "说话简短直接，先结论后细节；技术话题给可运行代码；默认中文。"},
            {"严谨工手", "先结论后推理；不确定就明说、不臆造；代码必须可运行且带边界处理。"},
            {"阿教", "像耐心的老师，一步步讲原理，给例子与类比；先讲清再动手。"},
            {"脑洞", "头脑风暴模式，给多个方案并说取舍；语言轻松但关键处准确。"},
            {"快答", "尽可能简短、要点式回答；不废话，除非要求展开。"},
        };
        for (String[] p : presets) {
            TextView chip = new TextView(this);
            chip.setText(p[0]);
            chip.setTextSize(13);
            int pad = 14 * d;
            int gap = 6 * d;
            chip.setPadding(pad, gap, pad, gap);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant));
            chip.setOnClickListener(vv -> {
                nameBox.setText(p[0]);
                cardBox.setText(p[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
    }

    // 取强调色/语义色：走 ContextCompat，自动跟随深浅色资源限定符；
    // 强调色 overlay 切换靠 recreate() 重建，这里读到的永远是当前主题的对应值
    private int getThemeColor(int resId) {
        return androidx.core.content.ContextCompat.getColor(this, resId);
    }

    // ============ Web 服务（保留原逻辑） ============

    private boolean webuiOn;
    private int webuiPollCount;
    private final android.os.Handler webuiPoll = new android.os.Handler();
    private Runnable webuiPollRunnable;

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
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统无障碍设置开完服务回来，刷新 GUI 状态
        refreshGuiState(findViewById(R.id.set_gui_state));
        // 容器环境状态也可能因部署完成/失败而变
        refreshProotState(findViewById(R.id.set_proot_state));
    }

    // 容器环境（proot）：一键部署（多线程 + 断点 + 国内镜像回退），进度走回调，可取消
    private void bindProot(final TextView stateTv, final ProgressBar prog,
                           final TextView progLabel, final Button deploy, final Button cancel) {
        refreshProotState(stateTv);
        deploy.setOnClickListener(v -> {
            final com.happyagent.mobile.data.ProotEnv env =
                    com.happyagent.mobile.data.ProotEnv.get(HappyAgentApplication.get());
            deploy.setVisibility(View.GONE);
            cancel.setVisibility(View.VISIBLE);
            prog.setVisibility(View.VISIBLE);
            progLabel.setVisibility(View.VISIBLE);
            prog.setProgress(0);
            progLabel.setText("准备下载…");
            env.deploy(new com.happyagent.mobile.data.ProotEnv.Progress() {
                @Override
                public void on(String phase, int pct, String msg) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            prog.setMax(100);
                            prog.setProgress(pct);
                            progLabel.setText(msg);
                        }
                    });
                }
                @Override
                public void onDone(boolean ok, String msg) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deploy.setVisibility(View.VISIBLE);
                            cancel.setVisibility(View.GONE);
                            prog.setVisibility(View.GONE);
                            progLabel.setVisibility(View.GONE);
                            Toast.makeText(SettingsActivity.this,
                                    ok ? "容器已部署就绪" : ("部署失败：" + msg),
                                    Toast.LENGTH_LONG).show();
                            refreshProotState(stateTv);
                        }
                    });
                }
            });
        });
        cancel.setOnClickListener(v -> {
            com.happyagent.mobile.data.ProotEnv.get(HappyAgentApplication.get()).cancelDeploy();
            deploy.setVisibility(View.VISIBLE);
            cancel.setVisibility(View.GONE);
            prog.setVisibility(View.GONE);
            progLabel.setVisibility(View.GONE);
            refreshProotState(stateTv);
        });
    }

    // 刷新容器状态显示：已就绪(架构+探活) / 未部署 / 半成品
    private void refreshProotState(TextView stateTv) {
        com.happyagent.mobile.data.ProotEnv env =
                com.happyagent.mobile.data.ProotEnv.get(HappyAgentApplication.get());
        if (env.isReady()) {
            stateTv.setText("● 已就绪（" + env.abiName() + "）");
            stateTv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_done));
        } else if (env.isDeploying()) {
            stateTv.setText("◌ 部署中…");
            stateTv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.acc_amber));
        } else {
            stateTv.setText("○ 未部署（" + env.abiName() + "）");
            stateTv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_failed));
        }
    }

    // GUI 自动化（无障碍服务）是否已开启
    private void refreshGuiState(TextView stateTv) {
        boolean on = com.happyagent.mobile.service.GuardService.isRunning();
        stateTv.setText(on ? "● 无障碍服务已开启" : "○ 无障碍服务未开启");
        stateTv.setTextColor(on
                ? androidx.core.content.ContextCompat.getColor(this, R.color.status_done)
                : androidx.core.content.ContextCompat.getColor(this, R.color.status_failed));
    }

    @Override
    protected void onDestroy() {
        stopWebuiPoll();
        super.onDestroy();
    }
}
