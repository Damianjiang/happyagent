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
import com.google.android.material.button.MaterialButton;

import com.happyagent.mobile.CrashHandler;
import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Message;
import com.happyagent.mobile.model.Models.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

// 对话页：聊天气泡流（user/tool/assistant 三类）+ 底部输入发送，多轮上下文，
// 列表局部刷新，工具调用可见，全程离线/在线可用
public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private TextView modelLabel, status, empty;
    private EditText promptBox;
    private ImageButton sendBtn;
    private RecyclerView recycler;
    private LinearLayoutManager layoutMgr;

    private View controlsRow;
    private MaterialButton pauseBtn, resumeBtn, cancelBtn;

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
        controlsRow = findViewById(R.id.detail_controls);
        pauseBtn = findViewById(R.id.detail_pause);
        resumeBtn = findViewById(R.id.detail_resume);
        cancelBtn = findViewById(R.id.detail_cancel);
        pauseBtn.setOnClickListener(v -> AgentBackend.get().pauseTask());
        resumeBtn.setOnClickListener(v -> AgentBackend.get().resumeTask());
        cancelBtn.setOnClickListener(v -> cancelRunning());

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
        layoutMgr = new LinearLayoutManager(this);
        layoutMgr.setStackFromEnd(true);   // 从底部往上排，聊天习惯
        recycler.setLayoutManager(layoutMgr);
        recycler.setAdapter(adapter);
        recycler.setNestedScrollingEnabled(false);

        ui = chatOf(s.messages);
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

        // 立刻显示用户气泡（局部 insert，不全量刷新）
        Message bubble = new Message("user", p, System.currentTimeMillis());
        ui.add(bubble);
        adapter.notifyTailAdded(1);
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
            updateControls();
            mainHandler.postDelayed(pollTask, 250);
        } catch (Exception e) {
            CrashHandler.showFrom(e);
        }
    }

    private void cancelRunning() {
        AgentBackend.get().cancelTask();
    }

    // 控制条可见性与按钮可用性，跟任务运行时/暂停状态同步
    private void updateControls() {
        AgentBackend backend = AgentBackend.get();
        boolean run = backend.isTaskRunning();
        controlsRow.setVisibility(run ? View.VISIBLE : View.GONE);
        if (run) {
            boolean paused = backend.isTaskPaused();
            pauseBtn.setVisibility(paused ? View.GONE : View.VISIBLE);
            resumeBtn.setVisibility(paused ? View.VISIBLE : View.GONE);
        }
    }

    private void checkRunning() {
        if (running == null) return;
        if (!running.isDone()) {
            updateControls();
            mainHandler.postDelayed(pollTask, 250);
            return;
        }
        try {
            Session s = running.get();
            // 全量刷新为持久化后的对话；只 insert 新增段（会话是纯追加）
            int oldCount = adapter.count();
            List<Message> full = chatOf(s.messages);
            ui = full;
            adapter.submit(full);
            int added = full.size() - oldCount;
            if (added > 0) adapter.notifyTailAdded(added);
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

    // 聊天流展示 user / tool / assistant 三类（工具调用可见，对齐 Operit）
    private List<Message> chatOf(List<Message> all) {
        List<Message> out = new ArrayList<Message>();
        for (Message m : all) {
            if (m.role.equals("user") || m.role.equals("tool") || m.role.equals("assistant")) out.add(m);
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
        recycler.post(() -> {
            if (adapter.count() > 0) layoutMgr.scrollToPosition(adapter.count() - 1);
        });
    }

    @Override
    protected void onDestroy() {
        if (pollTask != null) mainHandler.removeCallbacks(pollTask);
        if (running != null) running.cancel(true);
        super.onDestroy();
    }

    // 聊天气泡 adapter：user 靠右、assistant 靠左、tool 中间小灰字
    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<Message> items = new ArrayList<Message>();

        int count() {
            return items.size();
        }

        void submit(List<Message> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        // 只标记新增的尾部条目，避免全量 rebind（聊天性能关键）
        void notifyTailAdded(int n) {
            int from = items.size() - n;
            if (from < 0) from = 0;
            notifyItemRangeInserted(from, n);
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
            boolean isTool = m.role.equals("tool");
            boolean isAi = m.role.equals("assistant");
            h.userRow.setVisibility(isUser ? View.VISIBLE : View.GONE);
            h.aiRow.setVisibility(isAi ? View.VISIBLE : View.GONE);
            h.toolRow.setVisibility(isTool ? View.VISIBLE : View.GONE);
            if (isUser) {
                h.userBubble.setText(m.text);
            } else if (isAi) {
                h.aiBubble.setText(m.text);
            } else if (isTool) {
                h.toolLine.setText(m.text);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView userBubble, aiBubble, toolLine;
            final View userRow, aiRow, toolRow;

            VH(View v) {
                super(v);
                userBubble = v.findViewById(R.id.bubble_user);
                aiBubble = v.findViewById(R.id.bubble_ai);
                toolLine = v.findViewById(R.id.bubble_tool);
                userRow = v.findViewById(R.id.row_user);
                aiRow = v.findViewById(R.id.row_ai);
                toolRow = v.findViewById(R.id.row_tool);
            }
        }
    }
}
