package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.R;
import com.happyagent.mobile.data.ProotEnv;

// 容器终端：proot 就绪后可手动在 Alpine 里跑命令（apk add / python / git 等，免 root Linux 终端）。
// 未部署时诚实引导一键部署（复用 ProotEnv.deploy，与设置页同套多线程+断点+国内镜像进度），绝不假装能跑。
public class TerminalActivity extends AppCompatActivity {

    private ProotEnv env;
    private TextView stateTv, out, deployLabel;
    private EditText input;
    private MaterialButton runBtn, deployBtn, deployCancel;
    private ProgressBar deployProgress;
    private ScrollView outScroll;
    private LinearLayout deployBox;
    // probe / 跑命令都走这单线程池，避免主线程阻塞 + 每次 new Thread 累积
    private final java.util.concurrent.ExecutorService execPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_terminal);
        env = ProotEnv.get(HappyAgentApplication.get());

        MaterialToolbar t = findViewById(R.id.term_toolbar);
        t.setNavigationOnClickListener(v -> finish());

        stateTv = findViewById(R.id.term_state);
        out = findViewById(R.id.term_output);
        outScroll = findViewById(R.id.term_output_scroll);
        deployBox = findViewById(R.id.term_deploy_box);
        deployProgress = findViewById(R.id.term_deploy_progress);
        deployLabel = findViewById(R.id.term_deploy_label);
        deployBtn = findViewById(R.id.term_deploy);
        deployCancel = findViewById(R.id.term_deploy_cancel);
        input = findViewById(R.id.term_input);
        runBtn = findViewById(R.id.term_run);

        buildChips();
        out.append("$ " + env.abiName() + " alpine 容器终端\n");
        out.append("容器内可跑：apk add / python / git / gcc 等（免 root）\n\n");
        runBtn.setOnClickListener(v -> run());
        input.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == 100) { run(); return true; }
            return false;
        });
        deployBtn.setOnClickListener(v -> startDeploy());
        deployCancel.setOnClickListener(v -> {
            env.cancelDeploy();
            refreshState();
        });
        refreshState();
    }

    // 常用命令快捷 chip：点一下填进输入框
    private void buildChips() {
        LinearLayout row = findViewById(R.id.term_chips);
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        String[] cmds = {"apk add python3", "apk add nodejs", "apk add git", "python3 --version",
                "gcc --version", "cat /etc/alpine-release", "df -h", "whoami"};
        for (String c : cmds) {
            TextView chip = new TextView(this);
            chip.setText(c);
            chip.setTextSize(12);
            chip.setPadding(14 * d, 6 * d, 14 * d, 6 * d);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant));
            chip.setOnClickListener(vv -> input.setText(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 6 * d;
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
    }

    private void refreshState() {
        int doneColor = androidx.core.content.ContextCompat.getColor(this, R.color.status_done);
        int failColor = androidx.core.content.ContextCompat.getColor(this, R.color.status_failed);
        int amberColor = androidx.core.content.ContextCompat.getColor(this, R.color.acc_amber);
        if (env.isReady()) {
            stateTv.setText("● 已就绪（" + env.abiName() + "）");
            stateTv.setTextColor(doneColor);
            deployBox.setVisibility(View.GONE);
            runBtn.setEnabled(true);
            input.setEnabled(true);
        } else if (env.isDeploying()) {
            stateTv.setText("● 部署中…");
            stateTv.setTextColor(amberColor);
            deployBox.setVisibility(View.VISIBLE);
            deployBtn.setVisibility(View.GONE);
            deployCancel.setVisibility(View.VISIBLE);
            deployProgress.setVisibility(View.VISIBLE);
            deployLabel.setVisibility(View.VISIBLE);
            runBtn.setEnabled(false);
            input.setEnabled(false);
        } else {
            stateTv.setText("○ 未就绪");
            stateTv.setTextColor(failColor);
            deployBox.setVisibility(View.VISIBLE);
            deployBtn.setVisibility(View.VISIBLE);
            deployCancel.setVisibility(View.GONE);
            deployProgress.setVisibility(View.GONE);
            deployLabel.setVisibility(View.VISIBLE);
            deployLabel.setText("探活中…");
            runBtn.setEnabled(false);
            input.setEnabled(false);
            // 探活是同步 exec 容器进程，放后台避免卡主线程（半成品态）
            execPool.execute(() -> {
                final String probe = env.probeExec();
                runOnUiThread(() -> {
                    deployLabel.setText("探活：" + probe);
                    // 探活耗时可能已完成部署/失败，再刷一次状态对齐
                    if (!env.isReady() && !env.isDeploying()) {
                        runBtn.setEnabled(false);
                        input.setEnabled(false);
                    }
                });
            });
        }
    }

    private void startDeploy() {
        deployBtn.setVisibility(View.GONE);
        deployCancel.setVisibility(View.VISIBLE);
        deployProgress.setVisibility(View.VISIBLE);
        deployLabel.setVisibility(View.VISIBLE);
        deployProgress.setProgress(0);
        deployLabel.setText("准备下载…");
        out.append("\n[部署容器]\n");
        scrollBottom();
        env.deploy(new ProotEnv.Progress() {
            @Override
            public void on(String phase, int pct, String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        deployProgress.setMax(100);
                        deployProgress.setProgress(pct);
                        deployLabel.setText(phase + " " + pct + "% · " + msg);
                    }
                });
            }

            @Override
            public void onDone(final boolean ok, final String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        out.append(ok ? "容器已部署就绪\n\n" : "部署失败：" + msg + "\n\n");
                        scrollBottom();
                        Toast.makeText(TerminalActivity.this,
                                ok ? "容器已就绪" : ("部署失败：" + msg), Toast.LENGTH_LONG).show();
                        refreshState();
                    }
                });
            }
        });
    }

    // 在容器里跑一条命令（后台线程，避免卡 UI；API 23 安全）
    private void run() {
        final String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) return;
        if (!env.isReady()) {
            out.append("$ " + cmd + "\n[容器未就绪，无法运行]\n\n");
            scrollBottom();
            return;
        }
        runBtn.setEnabled(false);
        out.append("$ " + cmd + "\n");
        scrollBottom();
        final long t0 = System.currentTimeMillis();
        execPool.execute(new Runnable() {
            @Override
            public void run() {
                final String[] holder = new String[1];
                try {
                    holder[0] = env.execInContainer(cmd, 60000);
                } catch (Exception e) {
                    holder[0] = "异常：" + e.getMessage();
                }
                final String r = holder[0];
                final long dt = System.currentTimeMillis() - t0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        out.append(r == null ? "" : r);
                        out.append("\n（" + dt + "ms）\n\n");
                        scrollBottom();
                        runBtn.setEnabled(true);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        execPool.shutdownNow();
        super.onDestroy();
    }

    private void scrollBottom() {
        outScroll.post(new Runnable() {
            @Override
            public void run() {
                outScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
