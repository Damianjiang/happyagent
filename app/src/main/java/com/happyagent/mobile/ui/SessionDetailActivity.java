package com.happyagent.mobile.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.happyagent.mobile.model.Models.Attachment;
import com.happyagent.mobile.model.Models.Message;
import com.happyagent.mobile.model.Models.Session;
import com.happyagent.mobile.tools.FileTools;

import java.util.ArrayList;
import java.util.List;

// 对话页：聊天气泡流（user/tool/assistant 三类）+ 底部输入/附件/发送，多轮上下文，
// 图片文件附件，任务跑在单例后端，离开页面可继续，回来重挂轮询。
public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";
    private static final int PICK_IMAGE = 101;
    private static final int PICK_FILE = 102;

    private TextView empty;
    private EditText promptBox;
    private ImageButton sendBtn, pickImage, pickFile;
    private RecyclerView recycler;
    private LinearLayoutManager layoutMgr;
    private MaterialToolbar toolbar;

    private View controlsRow, attachStrip;
    private MaterialButton pauseBtn, resumeBtn, cancelBtn;
    private LinearLayout attachItems;
    private String model;

    private String sessionId;
    private ChatAdapter adapter;
    private final List<Attachment> pending = new ArrayList<Attachment>();
    // 运行中已实时增量显示过的工具步骤数（任务结束时随 dump 全量重置）
    private int liveToolCount = 0;

    private android.os.Handler mainHandler;
    private Runnable pollTask;
    private FileTools fileTools;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_session_detail);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        mainHandler = new android.os.Handler(getMainLooper());
        fileTools = new FileTools(getApplication());

        toolbar = findViewById(R.id.detail_toolbar);
        promptBox = findViewById(R.id.detail_prompt);
        sendBtn = findViewById(R.id.detail_send);
        pickImage = findViewById(R.id.detail_pick_image);
        pickFile = findViewById(R.id.detail_pick_file);
        recycler = findViewById(R.id.chat_recycler);
        empty = findViewById(R.id.chat_empty);
        controlsRow = findViewById(R.id.detail_controls);
        attachStrip = findViewById(R.id.detail_attach_strip);
        attachItems = findViewById(R.id.attach_strip_items);
        pauseBtn = findViewById(R.id.detail_pause);
        resumeBtn = findViewById(R.id.detail_resume);
        cancelBtn = findViewById(R.id.detail_cancel);

        pauseBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().pauseTask(); });
        resumeBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().resumeTask(); });
        cancelBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().cancelTask(); });
        pickImage.setOnClickListener(v -> pick(PICK_IMAGE));
        pickFile.setOnClickListener(v -> pick(PICK_FILE));

        AgentBackend backend = AgentBackend.get();
        model = backend.getConfig().model;
        Session s = backend.getSession(sessionId);
        if (s == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 模型名放到顶栏副标题，卡片区保持干净
        toolbar.setTitle(s.title);
        toolbar.setSubtitle("模型: " + model);
        toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new ChatAdapter();
        layoutMgr = new LinearLayoutManager(this);
        layoutMgr.setStackFromEnd(true);
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

    private void pick(int req) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setType(req == PICK_IMAGE ? "image/*" : "*/*");
        startActivityForResult(android.content.Intent.createChooser(i,
                req == PICK_IMAGE ? "选图片" : "选文件"), req);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Attachment a;
        try {
            a = fileTools.saveAttachment(getContentResolver(), data.getData(),
                    displayName(data.getData()));
        } catch (Exception e) {
            a = null;
        }
        if (a == null) {
            Toast.makeText(this, "读取附件失败", Toast.LENGTH_SHORT).show();
            return;
        }
        pending.add(a);
        renderPending();
    }

    private String displayName(Uri uri) {
        String out = null;
        android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) out = c.getString(0);
            } finally {
                c.close();
            }
        }
        return out;
    }

    // 待发附件预览：缩略图/文件名 + 移除按钮
    private void renderPending() {
        attachItems.removeAllViews();
        attachStrip.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < pending.size(); i++) {
            Attachment a = pending.get(i);
            attachItems.addView(makeChip(a, i));
        }
    }

    private View makeChip(Attachment a, int idx) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int m = (int) (6 * getResources().getDisplayMetrics().density);
        chip.setPadding(m, m, m / 2, m);
        chip.setBackgroundResource(R.drawable.bg_chip);

        if (a.isImage()) {
            ImageView iv = new ImageView(this);
            int sz = (int) (56 * getResources().getDisplayMetrics().density);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setImageBitmap(thumb(a.path, sz));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            chip.addView(iv);
        } else {
            TextView label = new TextView(this);
            label.setText("📄 " + a.fileName);
            label.setTextSize(12);
            label.setTextColor(getResources().getColor(R.color.on_surface_variant));
            label.setMaxWidth((int) (140 * getResources().getDisplayMetrics().density));
            chip.addView(label);
        }

        TextView x = new TextView(this);
        x.setText(" ×");
        x.setTextSize(16);
        x.setTextColor(getResources().getColor(R.color.on_surface_variant));
        x.setOnClickListener(v -> {
            pending.remove(idx);
            renderPending();
        });
        chip.addView(x);
        return chip;
    }

    private Bitmap thumb(String path, int px) {
        int s = 1;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int w = o.outWidth, h = o.outHeight;
        while (w / s > px && h / s > px) s *= 2;
        o.inSampleSize = s;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, o);
    }

    // 回来时若后台仍有本会话任务在跑，重新挂上轮询
    @Override
    protected void onResume() {
        super.onResume();
        if (AgentBackend.get().isSessionRunning(sessionId)) startPolling();
    }

    private void send() {
        String p = promptBox.getText().toString().trim();
        boolean hasFiles = pending.size() > 0;
        if (p.isEmpty() && !hasFiles) return;
        if (anyRunning()) return;

        List<Attachment> atts = new ArrayList<Attachment>(pending);
        String text = p.isEmpty() ? ("(附件 " + atts.size() + " 个)") : p;

        // 乐观插用户气泡（带附件），adapter 是唯一数据源
        adapter.append(new Message("user", text, System.currentTimeMillis(), atts));
        pending.clear();
        renderPending();
        updateEmpty();
        scrollBottom();

        promptBox.setText("");
        sendBtn.setEnabled(false);
        liveToolCount = 0;   // 新任务从头实时计数
        try {
            AgentBackend.get().runTask(sessionId, text, atts);
            startPolling();
            Haptics.action();   // 发送并启动任务时一次稍强反馈（开关开才响）
        } catch (Exception e) {
            CrashHandler.showFrom(e);
        }
    }

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

    private boolean anyRunning() {
        return AgentBackend.get().isSessionRunning(sessionId);
    }

    private void checkRunning() {
        AgentBackend backend = AgentBackend.get();
        if (anyRunning()) {
            updateControls();
            // 实时增量：把比已显示多的工具步骤立即 append，不必等任务跑完
            List<Message> live = backend.peekRunningToolSteps();
            if (live.size() > liveToolCount) {
                for (int i = liveToolCount; i < live.size(); i++) {
                    adapter.append(live.get(i));
                }
                liveToolCount = live.size();
                updateEmpty();
                scrollBottom();
            }
            mainHandler.postDelayed(pollTask, 250);
            return;
        }
        Session s = backend.getSession(sessionId);
        if (s != null) {
            // 结束：全量重建（会话已含全部 user/tool/assistant），重置实时计数避免重复
            adapter.submit(chatOf(s.messages));
            liveToolCount = 0;
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
        if (pollTask != null) {
            mainHandler.removeCallbacks(pollTask);
            pollTask = null;
        }
        super.onDestroy();
    }

    // 聊天气泡 adapter：user 靠右(带附件)、assistant 靠左、tool 中间小灰字
    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<Message> items = new ArrayList<Message>();
        private final float chatSizeSp;

        ChatAdapter() {
            com.happyagent.mobile.data.Prefs p = new com.happyagent.mobile.data.Prefs(
                    com.happyagent.mobile.HappyAgentApplication.get());
            int size = p.getInt(com.happyagent.mobile.data.Prefs.KEY_CHAT_TEXT_SIZE, 0);
            this.chatSizeSp = size == 2 ? 18f : (size == 1 ? 16f : 14f);
        }

        // 按个性化字号键调整气泡文字（工具行保持小灰字不动）
        private void applyChatSize(android.widget.TextView tv) {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, chatSizeSp);
        }

        int count() {
            return items.size();
        }

        void submit(List<Message> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        void append(Message m) {
            items.add(m);
            notifyItemInserted(items.size() - 1);
        }

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
                applyChatSize(h.userBubble);
                bindUserAttachments(h, m);
            } else if (isAi) {
                h.aiBubble.setText(m.text);
                applyChatSize(h.aiBubble);
                // AI 回复可一键复制（引擎能答但 UI 没露出来的那块）
                final String aiText = m.text;
                h.aiBubbleCopy.setOnClickListener(v -> copyToClipboard(h.itemView.getContext(), aiText));
            } else if (isTool) {
                h.toolLine.setText(m.text);
                final String toolText = m.text;
                h.toolLineCopy.setOnClickListener(v -> copyToClipboard(h.itemView.getContext(), toolText));
            }
        }

        // 复制 AI 回复 / 工具输出到剪贴板
        private void copyToClipboard(android.content.Context ctx, String text) {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null || text == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("happy-agent", text));
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
        }

        // 用户气泡上方渲染附件：图片缩略图 + 文件 chip
        private void bindUserAttachments(VH h, Message m) {
            List<Attachment> atts = m.safeAttachments();
            h.userAttach.removeAllViews();
            if (atts.isEmpty()) {
                h.userAttach.setVisibility(View.GONE);
                return;
            }
            h.userAttach.setVisibility(View.VISIBLE);
            for (Attachment a : atts) {
                if (a.isImage()) {
                    ImageView iv = new ImageView(h.itemView.getContext());
                    int sz = dp(h, 72);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                    iv.setImageBitmap(thumbFor(a.path, sz));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundResource(R.drawable.bg_chip);
                    h.userAttach.addView(iv);
                } else {
                    TextView chip = new TextView(h.itemView.getContext());
                    chip.setText("📄 " + a.fileName);
                    chip.setTextSize(12);
                    chip.setTextColor(h.itemView.getContext().getResources().getColor(R.color.on_surface_variant));
                    chip.setPadding(dp(h, 8), dp(h, 4), dp(h, 8), dp(h, 4));
                    chip.setBackgroundResource(R.drawable.bg_chip);
                    h.userAttach.addView(chip);
                }
            }
        }

        private int dp(VH h, int v) {
            return (int) (v * h.itemView.getContext().getResources().getDisplayMetrics().density);
        }

        private Bitmap thumbFor(String path, int px) {
            int s = 1;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            while (o.outWidth / s > px && o.outHeight / s > px) s *= 2;
            o.inSampleSize = s;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, o);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView userBubble, aiBubble, toolLine;
            final View userRow, aiRow, toolRow;
            final LinearLayout userAttach;
            final android.widget.ImageView aiBubbleCopy, toolLineCopy;

            VH(View v) {
                super(v);
                userBubble = v.findViewById(R.id.bubble_user);
                aiBubble = v.findViewById(R.id.bubble_ai);
                toolLine = v.findViewById(R.id.bubble_tool);
                aiBubbleCopy = v.findViewById(R.id.bubble_ai_copy);
                toolLineCopy = v.findViewById(R.id.bubble_tool_copy);
                userRow = v.findViewById(R.id.row_user);
                aiRow = v.findViewById(R.id.row_ai);
                toolRow = v.findViewById(R.id.row_tool);
                userAttach = v.findViewById(R.id.user_attachments);
            }
        }
    }
}
