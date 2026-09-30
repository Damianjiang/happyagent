package com.agenz.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.agenz.mobile.CrashHandler;
import com.agenz.mobile.R;
import com.agenz.mobile.data.AgentBackend;
import com.agenz.mobile.model.Models.Session;

import java.util.concurrent.Future;

// 会话详情：输入任务 -> 跑本地引擎 -> 显示"计划/工具/总结"轨迹
public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private TextView title, agent, status, summary, modelLabel;
    private EditText promptBox;
    private Button runBtn;
    private ProgressBar progress;

    private String sessionId;
    private Future<Session> running;
    private android.os.Handler mainHandler;
    private Runnable pollTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        mainHandler = new android.os.Handler(getMainLooper());

        title = findViewById(R.id.detail_title);
        agent = findViewById(R.id.detail_agent);
        status = findViewById(R.id.detail_status);
        summary = findViewById(R.id.detail_summary);
        modelLabel = findViewById(R.id.detail_model);
        promptBox = findViewById(R.id.detail_prompt);
        runBtn = findViewById(R.id.detail_run);
        progress = findViewById(R.id.detail_progress);

        AgentBackend backend = AgentBackend.get();
        Session s = backend.getSession(sessionId);
        if (s == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        title.setText(s.title);
        agent.setText(s.agent);
        refreshStatus(s);
        modelLabel.setText("模型: " + backend.getConfig().model);
        if (!s.messages.isEmpty()) {
            summary.setText(s.messages.get(s.messages.size() - 1).text);
        }

        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = promptBox.getText().toString().trim();
                if (p.isEmpty()) {
                    Toast.makeText(SessionDetailActivity.this, "先输入任务", Toast.LENGTH_SHORT).show();
                    return;
                }
                runBtn.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                summary.setText("正在执行…");
                try {
                    running = AgentBackend.get().runTask(sessionId, p, AgentBackend.get().getConfig().model);
                    pollTask = new Runnable() {
                        @Override
                        public void run() {
                            checkRunning();
                        }
                    };
                    mainHandler.postDelayed(pollTask, 150);
                } catch (Exception e) {
                    CrashHandler.showFrom(e);
                }
            }
        });
    }

    // 每 150ms 看一眼任务完没完，完了就收尾
    private void checkRunning() {
        if (running == null) return;
        if (!running.isDone()) {
            mainHandler.postDelayed(pollTask, 150);
            return;
        }
        try {
            final Session s = running.get();
            progress.setVisibility(View.GONE);
            runBtn.setEnabled(true);
            refreshStatus(s);
            if (!s.messages.isEmpty()) {
                summary.setText(s.messages.get(s.messages.size() - 1).text);
            }
            Toast.makeText(this, "任务完成", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            progress.setVisibility(View.GONE);
            runBtn.setEnabled(true);
        }
    }

    private void refreshStatus(Session s) {
        status.setText(s.statusLabel());
    }

    @Override
    protected void onDestroy() {
        // 清掉挂着的轮询，避免页面关了还在后台找
        if (pollTask != null) mainHandler.removeCallbacks(pollTask);
        if (running != null) running.cancel(true);
        super.onDestroy();
    }
}
