package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import com.happyagent.mobile.CrashHandler;
import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Message;
import com.happyagent.mobile.model.Models.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

// 对话页：聊天气泡流 + 底部输入发送，支持多轮上下文，全程离线/在线可用
public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private TextView modelLabel, status, empty;
    private EditText promptBox;
    private ImageButton sendBtn;
    private RecyclerView recycler;

    private String sessionId;
    private ChatAdapter adapter;
    private List<Message> ui = new ArrayList<Message>();

    private Future<Session> running;
    private android.os.Handler mainHandler;
    private Runnable pollTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        mainHandler = new android.os.Handler(getMainLooper());

        modelLabel = findViewById(R.id.detail_model);
        status = findViewById(R.id.detail_status);
        promptBox = findViewById(R.id.detail_prompt);
        sendBtn = findViewById(R.id.detail_send);
        recycler = findViewById(R.id.chat_recycler);
        empty = findViewById(R.id.chat_empty);

        AgentBackend backend = AgentBackend.get();
        Session s = backend.getSession(sessionId);
        if (s == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setTitle(s.title);
        toolbar.setNavigationOnClickListener(v -> finish());

        modelLabel.setText("模型: " + backend.getConfig().model);
        refreshStatus(s);

        adapter = new ChatAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        ui = userAssistantOf(s.messages);
        adapter.submit(ui);
        updateEmpty();
        if (!ui.isEmpty()) scrollBottom();

        sendBtn.setOnClickListener(v -> send());
        promptBox.setOnEditorActionListener((tv, actionId, event) -> {
            send();
            return true;
        });
    }

    private void send() {
        String p = promptBox.getText().toString().trim();
        if (p.isEmpty()) return;
        if (running != null && !running.isDone()) return;   // 正在跑就不重复发

        // 立刻显示用户气泡
        ui.add(new Message("user", p, System.currentTimeMillis()));
        adapter.notifyItemInserted(ui.size() - 1);
        updateEmpty();
        scrollBottom();

        promptBox.setText("");
        sendBtn.setEnabled(false);
        try {
            running = AgentBackend.get().runTask(sessionId, p, AgentBackend.get().getConfig().model);
            pollTask = new Runnable() {
                @Override
                public void run() {
                    checkRunning();
                }
            };
            mainHandler.postDelayed(pollTask, 200);
        } catch (Exception e) {
            CrashHandler.showFrom(e);
        }
    }

    private void checkRunning() {
        if (running == null) return;
        if (!running.isDone()) {
            mainHandler.postDelayed(pollTask, 200);
            return;
        }
        try {
            Session s = running.get();
            ui = userAssistantOf(s.messages);
            adapter.submit(ui);
            refreshStatus(s);
            updateEmpty();
            scrollBottom();
            if (s.status == 3) {
                Toast.makeText(this, "任务失败：" + lastSystem(s), Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
        } finally {
            running = null;
            sendBtn.setEnabled(true);
        }
    }

    private void refreshStatus(Session s) {
        status.setText(s.statusLabel());
        int bg = s.status == 0 ? R.drawable.bg_status_running
                : (s.status == 3 ? R.drawable.bg_status_failed : R.drawable.bg_status_done);
        status.setBackgroundResource(bg);
    }

    // 只保留 user / assistant 用于聊天流
    private List<Message> userAssistantOf(List<Message> all) {
        List<Message> out = new ArrayList<Message>();
        for (Message m : all) {
            if (m.role.equals("user") || m.role.equals("assistant")) out.add(m);
        }
        return out;
    }

    private String lastSystem(Session s) {
        for (int i = s.messages.size() - 1; i >= 0; i--) {
            Message m = s.messages.get(i);
            if (m.role.equals("system")) return m.text;
        }
        return "未知错误";
    }

    private void updateEmpty() {
        empty.setVisibility(ui.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(ui.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void scrollBottom() {
        recycler.post(() -> recycler.scrollToPosition(ui.size() - 1));
    }

    @Override
    protected void onDestroy() {
        if (pollTask != null) mainHandler.removeCallbacks(pollTask);
        if (running != null) running.cancel(true);
        super.onDestroy();
    }

    // 聊天气泡 adapter：user 靠右、assistant 靠左
    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<Message> items = new ArrayList<Message>();

        void submit(List<Message> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Message m = items.get(pos);
            boolean isUser = m.role.equals("user");
            h.userBubble.setVisibility(isUser ? View.VISIBLE : View.GONE);
            h.aiWrap.setVisibility(isUser ? View.GONE : View.VISIBLE);
            if (isUser) {
                h.userBubble.setText(m.text);
            } else {
                h.aiBubble.setText(m.text);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView userBubble, aiBubble;
            final View aiWrap;

            VH(View v) {
                super(v);
                userBubble = v.findViewById(R.id.bubble_user);
                aiBubble = v.findViewById(R.id.bubble_ai);
                aiWrap = v.findViewById(R.id.bubble_ai_wrap);
            }
        }
    }
}
