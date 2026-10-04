package com.happyagent.mobile.data;

import android.content.Context;
import android.util.Log;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.model.Models.Config;
import com.happyagent.mobile.model.Models.Message;
import com.happyagent.mobile.model.Models.Session;
import com.happyagent.mobile.model.Models.Tool;
import com.happyagent.mobile.tools.FileTools;
import com.happyagent.mobile.tools.ReactAgent;
import com.happyagent.mobile.tools.ShellExecutor;
import com.happyagent.mobile.tools.SystemTools;
import com.happyagent.mobile.tools.TaskControl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// 单例后端：会话、工具、配置在此管理；落盘读写在单线程池，界面线程只拿快照。
public final class AgentBackend {

    private static final String TAG = "AgentBackend";
    private static final long WAIT_STEP_MS = 50;

    private static volatile AgentBackend instance;

    private final File storeDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    private List<Session> sessions = new ArrayList<Session>();
    private List<Tool> tools = new ArrayList<Tool>();
    private Config config = new Config();
    private volatile boolean loaded;

    // 正在跑的任务：Future 用于取消，TaskControl 用于暂停/继续
    private volatile Future<Session> runningFuture;
    private volatile TaskControl runningControl;
    private volatile String runningSessionId;
    // 当前任务的 ReAct trace（同步列表），供界面在任务运行中实时增量显示工具步骤
    private volatile java.util.List<com.happyagent.mobile.model.Models.Message> runningTrace;
    // Web 同步任务也登记进上面三个字段，用 webRunning 标记"跑在 HTTP 线程上"
    private volatile boolean webRunning;
    private final Object webAskLock = new Object();

    private AgentBackend() {
        Context ctx = HappyAgentApplication.get();
        storeDir = new File(ctx.getFilesDir(), "agent-store");
        if (!storeDir.exists()) storeDir.mkdirs();
    }

    public static AgentBackend get() {
        if (instance == null) {
            synchronized (AgentBackend.class) {
                if (instance == null) instance = new AgentBackend();
            }
        }
        return instance;
    }

    // 启动时后台读存档
    public void preload() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    try {
                        loadFromDisk();
                    } finally {
                        // 无论读成功还是抛异常都要落位：否则 loaded 永不置位，
                        // 界面线程每次 ensureLoaded 都会白等 5 秒（历史卡死点）
                        loaded = true;
                        lock.notifyAll();
                    }
                }
            }
        });
    }

    // 后台线程专用：等预加载完成（最坏 10 秒），绝不可在界面线程调（老安卓 6 上直接 ANR）
    private void ensureLoadedBlocking() {
        if (loaded) return;
        synchronized (lock) {
            int waited = 0;
            while (!loaded && waited++ < 200) {
                try {
                    lock.wait(WAIT_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!loaded) {
                try {
                    loadFromDisk();
                } catch (Exception e) {
                    sessions = new ArrayList<Session>();
                    tools = defaultTools();
                    config = new Config();
                    Log.w(TAG, "state sync load failed, using defaults", e);
                }
                loaded = true;
            }
        }
    }

    // 界面线程用：不阻塞。没加载好就安全走空白默认，后台 preload 完成后数据自然在
    private void ensureLoaded() {
        if (loaded) return;
        synchronized (lock) {
            if (!loaded) {
                // 不等待，当前数据为空集合/默认工具/空 config
                // 后台 preload() 完成后 loaded 会置 true，后续访问直接走快照
            }
        }
    }

    // ---- 会话 ----

    public List<Session> getSessions() {
        ensureLoaded();
        synchronized (lock) {
            return new ArrayList<Session>(sessions);
        }
    }

    public Session getSession(String id) {
        ensureLoaded();
        synchronized (lock) {
            for (Session s : sessions) {
                if (s.id.equals(id)) return s;
            }
            return null;
        }
    }

    public String createSession(String title, String agent) {
        ensureLoaded();
        // id 用 时间戳+随机串：避免同毫秒撞出多条同 id 空壳（旧版 bug：返回首页出现一堆重复新对话）
        String id = System.currentTimeMillis() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        Session s = new Session(
                id,
                title == null ? "New session" : title,
                agent == null ? config.agentName : agent,
                0, System.currentTimeMillis(), new ArrayList<Message>());
        synchronized (lock) {
            // 幂等兜底：同 id 已存在直接复用，绝不追加重复会话
            for (Session x : sessions) {
                if (x.id.equals(id)) return x.id;
            }
            sessions.add(0, s);
        }
        persist();
        return s.id;
    }

    // 跑一次任务：ReAct 多步，带多轮上下文 + 附件，持久化 user+工具步骤+assistant
    public Future<Session> runTask(String sessionId, String prompt,
                                   java.util.List<com.happyagent.mobile.model.Models.Attachment> attachments) {
        ensureLoaded();
        final Session session = getSession(sessionId);
        if (session == null) throw new IllegalStateException("unknown session " + sessionId);

        final java.util.List<com.happyagent.mobile.model.Models.Attachment> atts =
                attachments == null ? new java.util.ArrayList<com.happyagent.mobile.model.Models.Attachment>() : attachments;

        synchronized (lock) {
            session.status = 0;
            session.updatedAt = System.currentTimeMillis();
        }

        final TaskControl control = new TaskControl();
        runningControl = control;   // 在 submit 前就绑好，取消无空窗
        runningSessionId = sessionId;
        Future<Session> future = io.submit(new Callable<Session>() {
            @Override
            public Session call() {
                // 后台线程里确认数据已就绪（可安全等待）
                ensureLoadedBlocking();
                List<Message> trace = new java.util.concurrent.CopyOnWriteArrayList<Message>();
                runningTrace = trace;   // 绑上，界面运行中能实时增量显示工具步骤
                trace.add(new Message("user", prompt, System.currentTimeMillis(), atts));
                try {
                    // 取本会话已有 user/assistant 对话作为多轮上下文
                    List<Message> history = new ArrayList<Message>();
                    for (Message m : session.messages) {
                        if (m.role.equals("user") || m.role.equals("assistant")) {
                            history.add(m);
                        }
                    }
                    // 多步 ReAct：有 Key 走 LLM 驱动工具，没 Key 降级本地；FileTools 锚本会话沙箱
                    ReactAgent agent = new ReactAgent(getConfig(),
                            new FileTools(HappyAgentApplication.get(), session.id),
                            new ShellExecutor(HappyAgentApplication.get()),
                            new SystemTools(HappyAgentApplication.get()),
                            trace, control, getEnabledToolKeys());
                    agent.setRoleCard(currentRoleCard());
                    agent.setCustomSystemPrompt(currentSystemPrompt());
                    agent.setPromptTags(currentPromptTags());
                    agent.setMaxSteps(currentMaxSteps());
                    final String summary = agent.run(prompt, history, atts);
                    // 被取消/停止时，只记用户输入 + 已发生的工具步骤 + 停止说明，不标完成
                    List<Message> toolSteps = new ArrayList<Message>();
                    for (Message m : trace) {
                        if (m.role.equals("tool")) toolSteps.add(m);
                    }
                    boolean stopped = control.isCancelled();
                    synchronized (lock) {
                        session.messages.add(new Message("user", prompt, System.currentTimeMillis(), atts));
                        for (Message t : toolSteps) session.messages.add(t);
                        Message am = new Message("assistant", summary, System.currentTimeMillis());
                        am.thinking = agent.getLastThinking();   // 深度思考内容随消息落盘
                        session.messages.add(am);
                        session.status = stopped ? 3 : 2;
                        if (stopped) {
                            session.messages.add(new Message("system", "任务被停止",
                                    System.currentTimeMillis()));
                        }
                        session.updatedAt = System.currentTimeMillis();
                    }
                    persistNow();
                    return session;
                } catch (Exception e) {
                    Log.e(TAG, "runTask failed", e);
                    synchronized (lock) {
                        session.status = 3;
                        session.messages.add(new Message("system", "Task failed: " + e.getMessage(),
                                System.currentTimeMillis()));
                        session.updatedAt = System.currentTimeMillis();
                    }
                    persistNow();
                    return session;
                } finally {
                    clearRunning();
                }
            }
        });
        runningFuture = future;
        return future;
    }

    // Web 端任务入口：无会话则创建，同步跑完 ReAct。
    // 与 runTask 共用全局运行态字段（runningControl/runningSessionId/runningTrace），
    // 否则 Web 任务看不到控制条、/api/control 却能误控客户端任务（两套状态互相打架）。
    // 同一时刻只允许一个任务：已有任务在跑时返回 busy，由服务层回 409。
    public java.util.Map<String, Object> askFreeForm(String prompt, String sessionId) {
        String sid = (sessionId == null || sessionId.isEmpty())
                ? createSession("Web 任务", null) : sessionId;
        Session session = getSession(sid);
        if (session == null) {
            session = new Session(sid, "Web 任务", config.agentName, 0,
                    System.currentTimeMillis(), new ArrayList<Message>());
            synchronized (lock) {
                sessions.add(0, session);
            }
        }

        java.util.Map<String, Object> out = new java.util.HashMap<String, Object>();
        out.put("sessionId", sid);

        List<Message> history = new ArrayList<Message>();
        for (Message m : session.messages) {
            if (m.role.equals("user") || m.role.equals("assistant")) history.add(m);
        }
        List<Message> trace = new ArrayList<Message>();
        trace.add(new Message("user", prompt, System.currentTimeMillis()));
        TaskControl control = new TaskControl();

        // 原子门：HTTP 线程池有 4 条，检查+占位必须在同一把锁里，否则并发双跑
        synchronized (webAskLock) {
            if (isTaskRunning()) {
                out.put("busy", Boolean.TRUE);
                out.put("status", session.status);
                out.put("statusLabel", "已有任务在运行");
                return out;
            }
            // 登记为全局运行态：控制条 / 暂停 / 取消 / 实时步骤统一走这几个字段
            webRunning = true;
            runningControl = control;
            runningSessionId = sid;
            runningTrace = trace;
        }
        synchronized (lock) {
            session.status = 0;   // 列表实时显示"运行中"
            session.updatedAt = System.currentTimeMillis();
        }
        try {
            ReactAgent agent = new ReactAgent(getConfig(),
                    new FileTools(HappyAgentApplication.get(), sid),
                    new ShellExecutor(HappyAgentApplication.get()),
                    new SystemTools(HappyAgentApplication.get()),
                    trace, control, getEnabledToolKeys());
            agent.setRoleCard(currentRoleCard());
            agent.setCustomSystemPrompt(currentSystemPrompt());
            agent.setPromptTags(currentPromptTags());
            agent.setMaxSteps(currentMaxSteps());
            String summary = agent.run(prompt, history,
                    new ArrayList<com.happyagent.mobile.model.Models.Attachment>());
            List<Message> toolSteps = new ArrayList<Message>();
            for (Message m : trace) {
                if (m.role.equals("tool")) toolSteps.add(m);
            }
            synchronized (lock) {
                session.messages.add(new Message("user", prompt, System.currentTimeMillis()));
                for (Message t : toolSteps) session.messages.add(t);
                Message am = new Message("assistant", summary, System.currentTimeMillis());
                am.thinking = agent.getLastThinking();   // 深度思考内容随消息落盘
                session.messages.add(am);
                session.status = 2;
                session.updatedAt = System.currentTimeMillis();
            }
            persistNow();
            out.put("answer", summary);
            out.put("status", session.status);
            out.put("statusLabel", session.statusLabel());
            out.put("thinking", agent.getLastThinking());
            // 只回本轮 trace 的工具步骤（回读 session.messages 会把历史步骤也带上，前端重复渲染）
            java.util.ArrayList<String> steps = new java.util.ArrayList<String>();
            for (Message t : toolSteps) steps.add(t.text);
            out.put("toolSteps", steps);
        } catch (Exception e) {
            Log.e(TAG, "askFreeForm failed", e);
            synchronized (lock) {
                session.status = 3;
                session.messages.add(new Message("system", "任务失败：" + e.getMessage(),
                        System.currentTimeMillis()));
                session.updatedAt = System.currentTimeMillis();
            }
            persistNow();
            out.put("answer", "");
            out.put("status", 3);
            out.put("statusLabel", "失败");
        } finally {
            clearRunning();
            webRunning = false;
        }
        out.put("running", isTaskRunning());
        return out;
    }

    // Web 重新生成：在锁内截断到最后一条 user 并落盘，返回要重跑的 prompt（无则 null）
    public String prepareRegenerate(String sessionId) {
        Session s = getSession(sessionId);
        if (s == null) return null;
        String prompt;
        synchronized (lock) {
            int lastUser = -1;
            for (int i = s.messages.size() - 1; i >= 0; i--) {
                if ("user".equals(s.messages.get(i).role)) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser < 0) return null;
            prompt = s.messages.get(lastUser).text;
            while (s.messages.size() > lastUser + 1) s.messages.remove(s.messages.size() - 1);
            s.updatedAt = System.currentTimeMillis();
        }
        persist();
        return prompt;
    }

    // 当前运行中任务所属会话（Web 控制条按 sid 对号，防止跨任务串台）
    public String runningSessionId() {
        return runningSessionId;
    }

    private void clearRunning() {
        runningControl = null;
        runningFuture = null;
        runningSessionId = null;
        runningTrace = null;
    }

    // 角色卡 / 世界书：从 Prefs 读（每次任务前取，改了立即生效）。
    // 角色名非空时拼成「我是【角色名】：」引导句 + 设定正文，让称呼也真注入；否则只注入设定
    private String currentRoleCard() {
        Prefs p = new Prefs(HappyAgentApplication.get());
        String name = p.getString(Prefs.KEY_ROLE_NAME, "").trim();
        String card = p.getString(Prefs.KEY_ROLE_CARD, "").trim();
        if (card.isEmpty() && name.isEmpty()) return "";
        if (name.isEmpty()) return card;
        return "你的角色名是「" + name + "」，以下设定务必遵守。\n" + card;
    }

    // 任务最大步数：优先 Config.maxSteps（配置页可设），越界归一
    private int currentMaxSteps() {
        Config c = getConfig();
        return c.normalizedMaxSteps();
    }

    // 自定义系统提示词：设置页编辑的追加指令，真注入引擎（留空=只用内置默认）
    private String currentSystemPrompt() {
        Prefs p = new Prefs(HappyAgentApplication.get());
        return p.getString(Prefs.KEY_SYSTEM_PROMPT, "").trim();
    }

    // 标签 / 提示词片段：读 JSON，把启用段拼成正文注入系统提示词（全停用=空串）
    private String currentPromptTags() {
        Prefs p = new Prefs(HappyAgentApplication.get());
        java.util.List<PromptTags.Tag> tags = PromptTags.load(p.getString(Prefs.KEY_PROMPT_TAGS, ""));
        return PromptTags.enabledText(tags);
    }

    // 单轮 LLM 生成（无工具）：给"AI 生成角色卡"等一次性生成用。同步网络，调用方负责放后台线程。
    // 没 Key / 失败都如实返回说明串，不抛异常给 UI 层。
    public String llmGenerate(String prompt) {
        ReactAgent agent = new ReactAgent(getConfig(),
                new FileTools(HappyAgentApplication.get()),
                new ShellExecutor(HappyAgentApplication.get()),
                new SystemTools(HappyAgentApplication.get()),
                new ArrayList<Message>());
        try {
            return agent.oneShot(prompt);
        } catch (Exception e) {
            return "生成失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // 会话操作：重命名 / 删除 / 清空（数据层早就能，只是界面没入口）
    public boolean renameSession(String id, String title) {
        ensureLoaded();
        synchronized (lock) {
            for (int i = 0; i < sessions.size(); i++) {
                Session s = sessions.get(i);
                if (s.id.equals(id)) {
                    String clean = title == null ? "" : title.trim();
                    String newTitle = clean.isEmpty() ? "会话" : clean;
                    Session updated = new Session(s.id, newTitle, s.agent, s.status,
                            System.currentTimeMillis(), s.messages);
                    sessions.set(i, updated);
                    persist();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean deleteSession(String id) {
        ensureLoaded();
        synchronized (lock) {
            boolean found = false;
            // 倒序删光所有同 id（清掉历史 bug 留下的同 id 空壳，一次删除彻底干净）
            for (int i = sessions.size() - 1; i >= 0; i--) {
                if (sessions.get(i).id.equals(id)) {
                    sessions.remove(i);
                    found = true;
                }
            }
            if (found) persist();
            return found;
        }
    }

    public void clearSessions() {
        ensureLoaded();
        synchronized (lock) {
            sessions.clear();
            persist();
        }
    }

    // ---- 任务控制：暂停 / 继续 / 取消 ----

    public boolean isTaskRunning() {
        if (webRunning) return true;   // Web 同步任务：跑在 HTTP 线程，没有 Future
        return runningFuture != null && !runningFuture.isDone();
    }

    // 指定会话是不是当前在跑的那个任务（页面回来时只对号才重挂轮询/控制条）
    public boolean isSessionRunning(String sessionId) {
        return isTaskRunning() && sessionId != null && sessionId.equals(runningSessionId);
    }

    // 实时增量：返回当前运行中任务的工具步骤（role=tool），任务跑完前就能在界面滚动显示
    // 返回的是快照（CopyOnWriteArrayList 遍历安全），没在跑或没 trace 时返回空
    public List<Message> peekRunningToolSteps() {
        java.util.List<Message> t = runningTrace;
        if (t == null) return new ArrayList<Message>();
        List<Message> out = new ArrayList<Message>();
        for (Message m : t) {
            if (m.role.equals("tool")) out.add(m);
        }
        return out;
    }

    public boolean isTaskPaused() {
        TaskControl c = runningControl;
        return c != null && c.isPaused();
    }

    public void pauseTask() {
        if (runningControl != null) runningControl.pause();
    }

    public void resumeTask() {
        if (runningControl != null) runningControl.resume();
    }

    public void cancelTask() {
        TaskControl c = runningControl;
        if (c != null) c.cancel();      // 置位 + 断开当前 HTTP 连接，阻塞 read 立刻退出
        Future<Session> f = runningFuture;
        if (f != null) f.cancel(true);   // 再打断线程
    }

    // ---- 工具 ----

    public List<Tool> getTools() {
        ensureLoaded();
        synchronized (lock) {
            return new ArrayList<Tool>(tools);
        }
    }

    public Tool getTool(String id) {
        ensureLoaded();
        synchronized (lock) {
            for (Tool t : tools) {
                if (t.id.equals(id)) return t;
            }
            return null;
        }
    }

    public void setToolEnabled(String id, boolean enabled) {
        ensureLoaded();
        synchronized (lock) {
            for (int i = 0; i < tools.size(); i++) {
                Tool t = tools.get(i);
                if (t.id.equals(id)) {
                    tools.set(i, new Tool(t.id, t.name, t.desc, enabled, t.category));
                    break;
                }
            }
        }
        persist();
    }

    // 当前启用的工具分组 key；agent 据此门控：只把启用工具喂给模型
    public java.util.Set<String> getEnabledToolKeys() {
        ensureLoaded();
        java.util.Set<String> s = new java.util.HashSet<String>();
        synchronized (lock) {
            for (Tool t : tools) {
                if (t.enabled && t.id != null) s.add(t.id);
            }
        }
        return s;
    }

    // ---- 配置 ----

    public Config getConfig() {
        ensureLoaded();
        synchronized (lock) {
            return config;
        }
    }

    public void updateConfig(Config c) {
        ensureLoaded();
        synchronized (lock) {
            this.config = c;
        }
        persist();
    }

    // ---- 存档 ----

    // 统一丢给后台线程写，避免界面线程碰磁盘
    private void persist() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                persistNow();
            }
        });
    }

    // 落盘互斥：HTTP 线程（Web 任务）和 io 线程（客户端任务）都可能写 state.ser.tmp，
    // 不加锁会两个线程写同一 tmp 互相截断、损坏存档
    private final Object persistLock = new Object();

    private void persistNow() {
        synchronized (persistLock) {
            // 锁里只做快照（极短），写磁盘放锁外——不卡 UI 读
            State st;
            synchronized (lock) {
                st = new State();
                st.sessions = new ArrayList<Session>(sessions);
                st.tools = new ArrayList<Tool>(tools);
                st.config = config;
            }
            try {
                // 原子写：先写 tmp 再 rename，防崩在中间态导致旧存档丢失
                File tmp = new File(storeDir, "state.ser.tmp");
                File dest = new File(storeDir, "state.ser");
                FileOutputStream fos = new FileOutputStream(tmp);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(st);
                oos.close();
                fos.close();
                if (!tmp.renameTo(dest)) {
                    FileOutputStream f2 = new FileOutputStream(dest);
                    ObjectInputStream ois = new ObjectInputStream(new FileInputStream(tmp));
                    byte[] data = readAll(ois);
                    ois.close();
                    f2.write(data);
                    f2.close();
                    tmp.delete();
                }
            } catch (IOException e) {
                Log.e(TAG, "persist failed", e);
            }
        }
    }

    private static byte[] readAll(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    private void loadFromDisk() {
        File f = new File(storeDir, "state.ser");
        if (f.exists()) {
            try {
                ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f));
                State st = (State) ois.readObject();
                ois.close();
                // 不直接替换 sessions，而是合并（UI 线程在 load 前可能已 createSession 塞了新的）
                for (Session s : st.sessions) {
                    // 去重：内存里已有的不覆盖
                    boolean exists = false;
                    for (Session x : sessions) { if (x.id.equals(s.id)) { exists = true; break; } }
                    if (!exists) sessions.add(s);
                }
                tools = st.tools;
                config = st.config;
                mergeDefaultTools();
                // 启动清扫：任务不跨进程存活，进程重启后残留的"运行中/已暂停"都是假状态。
                // 一律标成"失败(中断)"，治列表永远转圈、明明跑完还显示"运行中"的假状态。
                boolean swept = false;
                for (Session s : sessions) {
                    if ((s.status == 0 || s.status == 1) && !s.id.equals(runningSessionId)) {
                        s.status = 3;
                        swept = true;
                    }
                }
                if (swept) persist();   // 把清扫结果落盘（io 线程内调用，入队后执行）
                Log.d(TAG, "loaded state from disk");
                return;
            } catch (Exception e) {
                Log.w(TAG, "state read failed, using defaults", e);
            }
        }
        tools = defaultTools();
        config = new Config();
    }

    // 默认工具集：每组对应引擎里一批工具，开关真正门控 agent 能调哪些
    private List<Tool> defaultTools() {
        List<Tool> l = new ArrayList<Tool>();
        l.add(new Tool("tool.file", "文件", "读 / 写 / 列目录 / 信息 / 存在性 / 移动 / 复制 / 压缩 / 解压（沙箱路径校验）", true, "dev"));
        l.add(new Tool("tool.search", "查找 / 搜索", "按名找文件、在文件里搜关键词", true, "dev"));
        l.add(new Tool("tool.shell", "Shell 沙箱", "/system/bin/sh 白名单命令 + 超时", false, "dev"));
        l.add(new Tool("tool.http", "HTTP 请求", "抓取网页 / 发 POST（可带 JSON 请求体）", false, "net"));
        l.add(new Tool("tool.text", "文本 / 数据处理", "base64、URL 编解码、JSON 取字段、大小写统计、四则运算", true, "dev"));
        l.add(new Tool("tool.system", "设备状态", "设备 / 电池 / 存储 / 网络 / 剪贴板查询（只读）", true, "dev"));
        l.add(new Tool("tool.gui", "GUI 自动化", "读屏幕 / 按文本点按 / 向可输入框输入（需先开启无障碍服务）", false, "sys"));
        l.add(new Tool("tool.proot", "容器环境 (proot)", "免 root Linux 容器跑命令：proot + Alpine，运行时下载部署（不进 APK），支持多线程/断点续传", false, "sys"));
        return l;
    }

    // 老存档只有早期几组；升级后把缺失的分组补上，开关默认开启
    private void mergeDefaultTools() {
        for (Tool t : defaultTools()) {
            boolean found = false;
            for (Tool x : tools) {
                if (t.id.equals(x.id)) { found = true; break; }
            }
            if (!found) tools.add(t);
        }
    }

    // 存档用的纯数据壳（类名/字段名被 R8 keep 住，计算出的 serialVersionUID 因此跨版本稳定，老存档可继续读）
    public static class State implements Serializable {
        public List<Session> sessions;
        public List<Tool> tools;
        public Config config;
    }
}
