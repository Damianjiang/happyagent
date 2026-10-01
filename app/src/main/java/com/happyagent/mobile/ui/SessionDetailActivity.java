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

// 对话页：聊天气泡流（user/tool/assistant 三类）+ 底部输入发送，多轮上下文，
// 列表局部刷新，工具调用可见。任务跑在单例后端，离开页面可继续，回来重挂轮询。
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
        cancelBtn.setOnClickListener(v -> AgentBackend.get().cancelTask());

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

        adapter.submit(chatOf(s.messages));
        updateEmpty();
        if (adapter.count() > 0) scrollBottom();

        sendBtn.setOnClickListener(v -> send());
        promptBox.setOnEditorActionListener((tv, actionId, event) -> {
            send();
            return true;
        });
    }

    // 回来时若后台仍有本会话任务在跑（比如页面被重建、或切回来），重新挂上轮询
    @Override
    protected void onResume() {
        super.onResume();
        if (AgentBackend.get().isSessionRunning(sessionId)) startPolling();
    }

    private void send() {
        String p = promptBox.getText().toString().trim();
        if (p.isEmpty()) return;
        if (anyRunning()) return;   // 有任务在跑就不重复发

        // 乐观插用户气泡（局部 insert），adapter 是界面上唯一数据源
        adapter.append(new Message("user", p, System.currentTimeMillis()));
        updateEmpty();
        scrollBottom();

        promptBox.setText("");
        sendBtn.setEnabled(false);
        try {
            AgentBackend.get().runTask(sessionId, p);
            startPolling();
        } catch (Exception e) {
            CrashHandler.showFrom(e);
        }
    }

    // 轮询由 mainHandler 驱动；send / onResume 都走这里，避免重复 post
    private void startPolling() {
        updateControls();
        if (pollTask == null) {
            pollTask = new Runnable() {
                @Override
                public void run() {
                    checkRunning();
                }
            };
        }
        mainHandler.postDelayed(pollTask, 250);
    }

    // 本会话是不是当前在跑的那个任务（App 全局单任务）
    private boolean anyRunning() {
        return AgentBackend.get().isSessionRunning(sessionId);
    }

    private void checkRunning() {
        if (anyRunning()) {
            updateControls();
            mainHandler.postDelayed(pollTask, 250);
            return;
        }
        // 跑完了：把本会话的权威对话补进列表（只插尾部，一次 notify）
        Session s = AgentBackend.get().getSession(sessionId);
        if (s != null) {
            List<Message> full = chatOf(s.messages);
            int prev = Math.min(adapter.count(), full.size());
            adapter.appendRange(full.subList(prev, full.size()));
            refreshStatus(s);
            updateEmpty();
            scrollBottom();
            if (s.status == 3) {
                Toast.makeText(this, "任务失败：" + lastSystem(s), Toast.LENGTH_LONG).show();
            }
        }
        pollTask = null;
        sendBtn.setEnabled(true);
        updateControls();
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
        boolean isEmpty = adapter.count() == 0;
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void scrollBottom() {
        recycler.post(() -> {
            if (adapter.count() > 0) layoutMgr.scrollToPosition(adapter.count() - 1);
        });
    }

    // 控制条跟任务运行/暂停状态同步；任务跑完或不是本会话就整行收起
    private void updateControls() {
        AgentBackend backend = AgentBackend.get();
        boolean run = backend.isSessionRunning(sessionId);
        controlsRow.setVisibility(run ? View.VISIBLE : View.GONE);
        if (run) {
            boolean paused = backend.isTaskPaused();
            pauseBtn.setVisibility(paused ? View.GONE : View.VISIBLE);
            resumeBtn.setVisibility(paused ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        // 任务在单例后端里跑，离开页面不打断它，只撤掉本页的轮询回调
        if (pollTask != null) {
            mainHandler.removeCallbacks(pollTask);
            pollTask = null;
        }
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

        // 追加一条（乐观气泡），只 insert 尾部
        void append(Message m) {
            items.add(m);
            notifyItemInserted(items.size() - 1);
        }

        // 追加一批尾部（任务完成时补工具/助手消息），一次 range notify，避免不一致
        void appendRange(List<Message> newTail) {
            if (newTail.isEmpty()) return;
            int from = items.size();
            items.addAll(newTail);
            notifyItemRangeInserted(from, newTail.size());
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
