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
import com.happyagent.mobile.data.TtsEngine;
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
    private static final int PICK_WORKSPACE = 104;

    private View empty;
    private EditText promptBox;
    private ImageButton sendBtn, pickImage, pickFile;
    private ImageButton micBtn;
    private RecyclerView recycler;
    private LinearLayoutManager layoutMgr;
    private MaterialToolbar toolbar;

    private View controlsRow, attachStrip;
    private MaterialButton pauseBtn, resumeBtn, cancelBtn;
    private TextView runStatus;
    private android.widget.TextView runDot;
    private LinearLayout attachItems;
    private String model;
    private TextView workspaceLabel;
    private MaterialButton workspacePick;

    private String sessionId;
    private ChatAdapter adapter;
    private final List<Attachment> pending = new ArrayList<Attachment>();
    // 运行中已实时增量显示过的工具步骤数（任务结束时随 dump 全量重置）
    private int liveToolCount = 0;
    // 麦克风听写：SpeechRecognizer 填进输入框（RECORD_AUDIO 运行时权限）
    private android.speech.SpeechRecognizer micRecognizer;
    private boolean micRecording;

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
        fileTools = new FileTools(getApplication(), sessionId);

        toolbar = findViewById(R.id.detail_toolbar);
        promptBox = findViewById(R.id.detail_prompt);
        sendBtn = findViewById(R.id.detail_send);
        pickImage = findViewById(R.id.detail_pick_image);
        pickFile = findViewById(R.id.detail_pick_file);
        micBtn = findViewById(R.id.detail_mic);
        recycler = findViewById(R.id.chat_recycler);
        empty = findViewById(R.id.chat_empty_wrap);
        controlsRow = findViewById(R.id.detail_controls);
        attachStrip = findViewById(R.id.detail_attach_strip);
        attachItems = findViewById(R.id.attach_strip_items);
        pauseBtn = findViewById(R.id.detail_pause);
        resumeBtn = findViewById(R.id.detail_resume);
        cancelBtn = findViewById(R.id.detail_cancel);
        runStatus = findViewById(R.id.detail_run_status);
        runDot = findViewById(R.id.detail_run_dot);
        workspaceLabel = findViewById(R.id.detail_workspace_label);
        workspacePick = findViewById(R.id.detail_workspace_pick);
        workspacePick.setOnClickListener(v -> pickWorkspace());
        refreshWorkspaceBar();

        pauseBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().pauseTask(); });
        resumeBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().resumeTask(); });
        cancelBtn.setOnClickListener(v -> { Haptics.tap(v); AgentBackend.get().cancelTask(); });
        pickImage.setOnClickListener(v -> pick(PICK_IMAGE));
        pickFile.setOnClickListener(v -> pick(PICK_FILE));
        if (micBtn != null) micBtn.setOnClickListener(v -> toggleMic());

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

    // 本会话工作区：显示当前沙箱目录，可导外部目录进来（SAF 拿不到裸 File，导入=真拷贝）
    private void refreshWorkspaceBar() {
        if (workspaceLabel == null) return;
        String ws = fileTools.getWorkspace();
        String last = new java.io.File(ws).getName();
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        workspaceLabel.setText("内置沙箱 · " + last);
    }

    // 选外部目录：SAF 选一个 tree，把它整个拷进本会话沙箱的 external/ 下
    private void pickWorkspace() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, PICK_WORKSPACE);
    }

    // 把选中的外部目录拷贝进本会话沙箱（后台线程，防卡 UI；只拷普通文件，限深度/数量防失控）
    private void importWorkspace(android.net.Uri treeUri) {
        final android.content.ContentResolver cr = getContentResolver();
        String rootName = "外部目录";
        android.database.Cursor nc = cr.query(treeUri,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null);
        if (nc != null) {
            try { if (nc.moveToFirst()) rootName = nc.getString(0); }
            finally { nc.close(); }
        }
        final String rootNameFinal = rootName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    n = copyTree(cr, treeUri, new java.io.File(fileTools.getWorkspace(), "external"), 0);
                } catch (Exception ignored) {}
                final int count = n;
                final String rn = rootNameFinal;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (count > 0) {
                            refreshWorkspaceBar();
                            android.widget.Toast.makeText(SessionDetailActivity.this,
                                    "已把「" + rn + "」的 " + count + " 个文件导入本会话工作区（external/）",
                                    android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            android.widget.Toast.makeText(SessionDetailActivity.this,
                                    "该目录没可读文件，或未授权读权限",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "ws-import").start();
    }

    // 递归列 DocumentsTree 下所有文件，拷到 dest；限深度/数量防失控
    private int copyTree(android.content.ContentResolver cr, android.net.Uri treeUri,
                         java.io.File dest, int depth) {
        int count = 0;
        if (depth > 6 || count > 500) return 0;
        String rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        if (rootId == null) return 0;
        android.net.Uri q = android.provider.DocumentsContract.buildChildDocumentsUri(
                treeUri.getAuthority(), rootId);
        android.database.Cursor c = cr.query(q, null, null, null, null);
        if (c == null) return 0;
        try {
            int iId = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iName = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int iType = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE);
            int iFlags = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_FLAGS);
            while (c.moveToNext()) {
                if (count >= 500) break;
                String docId = c.getString(iId);
                String name = c.getString(iName);
                String mime = iType >= 0 ? c.getString(iType) : "";
                int flags = iFlags >= 0 ? c.getInt(iFlags) : 0;
                boolean isDir = (flags & 2) != 0;
                if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                if (isDir && "vnd.android.document/directory".equals(mime)) {
                    android.net.Uri childUri = android.provider.DocumentsContract.buildDocumentUri(treeUri.getAuthority(), docId);
                    count += copyTree(cr, childUri, new java.io.File(dest, name), depth + 1);
                } else if (!isDir) {
                    android.net.Uri fileUri = android.provider.DocumentsContract.buildDocumentUri(treeUri.getAuthority(), docId);
                    if (copyFile(cr, fileUri, new java.io.File(dest, name))) count++;
                }
            }
        } finally {
            c.close();
        }
        return count;
    }

    private boolean copyFile(android.content.ContentResolver cr, android.net.Uri uri, java.io.File out) {
        if (!out.getParentFile().exists()) out.getParentFile().mkdirs();
        java.io.FileInputStream in = null;
        try {
            android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
            if (pfd == null) return false;
            in = new java.io.FileInputStream(pfd.getFileDescriptor());
            byte[] buf = new byte[8192];
            java.io.OutputStream os = new java.io.FileOutputStream(out);
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == PICK_WORKSPACE) {
            importWorkspace(data.getData());
            return;
        }
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

    // ---- 麦克风听写：点按开始/停止，识别结果自动追加进输入框 ----

    private void toggleMic() {
        if (micRecording) {
            stopMic();
            return;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 103);
            return;
        }
        startMic();
    }

    private void startMic() {
        final android.speech.SpeechRecognizer rec =
                android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        if (rec == null) {
            Toast.makeText(this, "这台设备没有可用的语音识别，请用键盘输入",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final android.content.Intent i = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                java.util.Locale.getDefault().toString());
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        rec.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buf) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int code) {
                Toast.makeText(SessionDetailActivity.this,
                        "没听清（错误 " + code + "），再说一次或用键盘", Toast.LENGTH_LONG).show();
                stopMic();
            }
            @Override public void onResults(android.os.Bundle b) {
                java.util.ArrayList<String> res =
                        b.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (res != null && !res.isEmpty()) {
                    promptBox.append(res.get(0));
                    promptBox.setSelection(promptBox.getText().length());
                }
                stopMic();
            }
            @Override public void onPartialResults(android.os.Bundle b) {}
            @Override public void onEvent(int eventType, android.os.Bundle b) {}
        });
        try {
            rec.startListening(i);
            micRecognizer = rec;
            micRecording = true;
            if (micBtn != null) {
                micBtn.setColorFilter(androidx.core.content.ContextCompat.getColor(
                        this, R.color.acc_default));
            }
            Toast.makeText(this, "正在听，说完自动填入…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try { rec.destroy(); } catch (Exception ignored) {}
            Toast.makeText(this, "启动语音识别失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopMic() {
        if (micRecognizer != null) {
            try {
                micRecognizer.stopListening();
                micRecognizer.destroy();
            } catch (Exception ignored) {}
            micRecognizer = null;
        }
        micRecording = false;
        if (micBtn != null) {
            micBtn.setColorFilter(androidx.core.content.ContextCompat.getColor(
                    this, R.color.on_surface_variant));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 103) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startMic();
            } else {
                Toast.makeText(this, "没有麦克风权限，无法语音输入", Toast.LENGTH_LONG).show();
            }
        }
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
            if (s.status == 2) {
                // 任务成功：开关开着就自动朗读 AI 最后一条回复（设置页 TTS 开关）
                String last = lastAssistantText(s);
                if (last != null) TtsEngine.get().speak(last);
            } else if (s.status == 3) {
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

    // 取最后一条 assistant 回复（TTS 朗读用）
    private String lastAssistantText(Session s) {
        for (int i = s.messages.size() - 1; i >= 0; i--) {
            if (s.messages.get(i).role.equals("assistant")) return s.messages.get(i).text;
        }
        return null;
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
            // 醒目状态行：点色 + 加粗文字 + 实时步数
            if (paused) {
                runDot.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_paused));
                runStatus.setText("已暂停");
            } else {
                int steps = backend.peekRunningToolSteps().size();
                runDot.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_done));
                runStatus.setText(steps > 0 ? "运行中 · 已调 " + steps + " 个工具" : "运行中…");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (pollTask != null) {
            mainHandler.removeCallbacks(pollTask);
            pollTask = null;
        }
        stopMic();
        TtsEngine.get().stop();
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

        // AI 回复 Markdown 渲染：像 Markdown 才走渲染，普通文本保持纯文本（不套等宽底/等宽字体）。
        // 渲染结果缓存到 m.md（transient），RecyclerView 复用同一 Message 时不重复重算。
        private void applyMarkdown(com.happyagent.mobile.model.Models.Message m, VH h) {
            String text = m.text == null ? "" : m.text;
            android.widget.TextView tv = h.aiBubble;
            if (!com.happyagent.mobile.tools.Markdown.looksLikeMarkdown(text)) {
                m.md = null;
                tv.setText(text);
                applyChatSize(tv);
                tv.setMovementMethod(null);
                return;
            }
            if (m.md == null) {
                m.md = com.happyagent.mobile.tools.Markdown.render(h.itemView.getContext(), text);
            }
            tv.setText(m.md);
            applyChatSize(tv);
            // 链接 span 可点：走系统浏览器打开
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
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
                // AI 回复走 Markdown 渲染（代码块/标题/列表/链接/粗体），非 Markdown 保持纯文本
                applyMarkdown(m, h);
                // AI 回复可一键复制（引擎能答但 UI 没露出来的那块）
                final String aiText = m.text;
                h.aiBubbleCopy.setOnClickListener(v -> copyToClipboard(h.itemView.getContext(), aiText));
            } else if (isTool) {
                String full = m.text;
                // 默认单行省略；点按行可展开看全文（工具输出长时不再"有功能没界面看"）
                if (h.expanded) {
                    h.toolLine.setSingleLine(false);
                    h.toolLine.setEllipsize(null);
                } else {
                    h.toolLine.setSingleLine(true);
                    h.toolLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                h.toolLine.setText(full);
                h.toolExpand.setRotation(h.expanded ? 180f : 0f);
                h.itemView.setOnClickListener(v -> {
                    h.expanded = !h.expanded;
                    if (h.expanded) {
                        h.toolLine.setSingleLine(false);
                        h.toolLine.setEllipsize(null);
                    } else {
                        h.toolLine.setSingleLine(true);
                        h.toolLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    }
                    h.toolLine.setText(full);
                    h.toolExpand.setRotation(h.expanded ? 180f : 0f);
                });
                final String toolText = full;
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
            final android.widget.ImageView aiBubbleCopy, toolLineCopy, toolExpand;
            // 工具行是否展开（ViewHolder 级；绑新行时重置为收起）
            boolean expanded;

            VH(View v) {
                super(v);
                userBubble = v.findViewById(R.id.bubble_user);
                aiBubble = v.findViewById(R.id.bubble_ai);
                toolLine = v.findViewById(R.id.bubble_tool);
                aiBubbleCopy = v.findViewById(R.id.bubble_ai_copy);
                toolLineCopy = v.findViewById(R.id.bubble_tool_copy);
                toolExpand = v.findViewById(R.id.bubble_tool_expand);
                userRow = v.findViewById(R.id.row_user);
                aiRow = v.findViewById(R.id.row_ai);
                toolRow = v.findViewById(R.id.row_tool);
                userAttach = v.findViewById(R.id.user_attachments);
                // 绑定新行（可能是复用 ViewHolder）时重置为收起态
                expanded = false;
            }
        }
    }
}
