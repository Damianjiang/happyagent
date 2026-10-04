package com.happyagent.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.R;

// 容器环境：proot + Alpine 一键部署（多线程+断点+镜像回退）、进度、取消、容器终端
public class ContainerSettingsActivity extends SectionSettingsActivity {

    @Override
    protected String title() {
        return "容器环境";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_container;
    }

    @Override
    protected void bind() {
        TextView stateTv = findViewById(R.id.set_proot_state);
        ProgressBar prog = findViewById(R.id.set_proot_progress);
        TextView progLabel = findViewById(R.id.set_proot_progress_label);
        Button deploy = findViewById(R.id.set_proot_deploy);
        Button cancel = findViewById(R.id.set_proot_cancel);
        bindProot(stateTv, prog, progLabel, deploy, cancel);

        ((com.google.android.material.button.MaterialButton) findViewById(R.id.set_proot_terminal))
                .setOnClickListener(vv ->
                        startActivity(new Intent(this, TerminalActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProotState(findViewById(R.id.set_proot_state));
    }

    private void bindProot(final TextView stateTv, final ProgressBar prog,
                           final TextView progLabel, final Button deploy, final Button cancel) {
        refreshProotState(stateTv);
        deploy.setOnClickListener(v -> {
            final com.happyagent.mobile.data.ProotEnv env =
                    com.happyagent.mobile.data.ProotEnv.get(HappyAgentApplication.get());
            deploy.setVisibility(android.view.View.GONE);
            cancel.setVisibility(android.view.View.VISIBLE);
            prog.setVisibility(android.view.View.VISIBLE);
            progLabel.setVisibility(android.view.View.VISIBLE);
            prog.setProgress(0);
            progLabel.setText("准备下载…");
            env.deploy(new com.happyagent.mobile.data.ProotEnv.Progress() {
                @Override
                public void on(String phase, int pct, String msg) {
                    runOnUiThread(() -> {
                        prog.setMax(100);
                        prog.setProgress(pct);
                        progLabel.setText(msg);
                    });
                }

                @Override
                public void onDone(boolean ok, String msg) {
                    runOnUiThread(() -> {
                        deploy.setVisibility(android.view.View.VISIBLE);
                        cancel.setVisibility(android.view.View.GONE);
                        prog.setVisibility(android.view.View.GONE);
                        progLabel.setVisibility(android.view.View.GONE);
                        Toast.makeText(ContainerSettingsActivity.this,
                                ok ? "容器已部署就绪" : ("部署失败：" + msg),
                                Toast.LENGTH_LONG).show();
                        refreshProotState(stateTv);
                    });
                }
            });
        });
        cancel.setOnClickListener(v -> {
            com.happyagent.mobile.data.ProotEnv.get(HappyAgentApplication.get()).cancelDeploy();
            deploy.setVisibility(android.view.View.VISIBLE);
            cancel.setVisibility(android.view.View.GONE);
            prog.setVisibility(android.view.View.GONE);
            progLabel.setVisibility(android.view.View.GONE);
            refreshProotState(stateTv);
        });
    }

    // 刷新容器状态显示：已就绪(架构+探活) / 未部署 / 部署中
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
}
