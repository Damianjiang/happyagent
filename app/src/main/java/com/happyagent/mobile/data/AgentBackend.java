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
                    loadFromDisk();
                    loaded = true;
                    lock.notifyAll();
                }
            }
        });
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (lock) {
            int waited = 0;
            while (!loaded && waited++ < 100) {
                try {
                    lock.wait(WAIT_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // 等太久则当场同步读一遍，保证界面不卡死
            if (!loaded) {
                loadFromDisk();
                loaded = true;
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
        Session s = new Session(
                String.valueOf(System.currentTimeMillis()),
                title == null ? "New session" : title,
                agent == null ? config.agentName : agent,
                0, System.currentTimeMillis(), new ArrayList<Message>());
        synchronized (lock) {
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
                List<Message> trace = new ArrayList<Message>();
                trace.add(new Message("user", prompt, System.currentTimeMillis(), atts));
                try {
                    // 取本会话已有 user/assistant 对话作为多轮上下文
                    List<Message> history = new ArrayList<Message>();
                    for (Message m : session.messages) {
                        if (m.role.equals("user") || m.role.equals("assistant")) {
                            history.add(m);
                        }
                    }
                    // 多步 ReAct：有 Key 走 LLM 驱动工具，没 Key 降级本地
                    ReactAgent agent = new ReactAgent(getConfig(),
                            new FileTools(HappyAgentApplication.get()),
                            new ShellExecutor(HappyAgentApplication.get()),
                            trace, control, getEnabledToolKeys());
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
                        session.messages.add(new Message("assistant", summary, System.currentTimeMillis()));
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

    // Web 端任务入口：无会话则创建，同步跑完 ReAct；独立于 UI 的 runningFuture
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
        try {
            ReactAgent agent = new ReactAgent(getConfig(),
                    new FileTools(HappyAgentApplication.get()),
                    new ShellExecutor(HappyAgentApplication.get()),
                    trace, control, getEnabledToolKeys());
            String summary = agent.run(prompt, history,
                    new ArrayList<com.happyagent.mobile.model.Models.Attachment>());
            List<Message> toolSteps = new ArrayList<Message>();
            for (Message m : trace) {
                if (m.role.equals("tool")) toolSteps.add(m);
            }
            synchronized (lock) {
                session.messages.add(new Message("user", prompt, System.currentTimeMillis()));
                for (Message t : toolSteps) session.messages.add(t);
                session.messages.add(new Message("assistant", summary, System.currentTimeMillis()));
                session.status = 2;
                session.updatedAt = System.currentTimeMillis();
            }
            persistNow();
            out.put("answer", summary);
            out.put("status", session.status);
            out.put("statusLabel", session.statusLabel());
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
        }
        out.put("running", isTaskRunning());
        return out;
    }

    private void clearRunning() {
        runningControl = null;
        runningFuture = null;
        runningSessionId = null;
    }

    // ---- 任务控制：暂停 / 继续 / 取消 ----

    public boolean isTaskRunning() {
        return runningFuture != null && !runningFuture.isDone();
    }

    // 指定会话是不是当前在跑的那个任务（页面回来时只对号才重挂轮询/控制条）
    public boolean isSessionRunning(String sessionId) {
        return isTaskRunning() && sessionId != null && sessionId.equals(runningSessionId);
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

    private void persistNow() {
        synchronized (lock) {
            try {
                State st = new State();
                st.sessions = new ArrayList<Session>(sessions);
                st.tools = new ArrayList<Tool>(tools);
                st.config = config;

                FileOutputStream fos = new FileOutputStream(new File(storeDir, "state.ser"));
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(st);
                oos.close();
                fos.close();
            } catch (IOException e) {
                Log.e(TAG, "persist failed", e);
            }
        }
    }

    private void loadFromDisk() {
        File f = new File(storeDir, "state.ser");
        if (f.exists()) {
            try {
                ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f));
                State st = (State) ois.readObject();
                ois.close();
                sessions = st.sessions;
                tools = st.tools;
                config = st.config;
                Log.d(TAG, "loaded state from disk");
                return;
            } catch (Exception e) {
                Log.w(TAG, "state read failed, using defaults", e);
            }
        }
        sessions = new ArrayList<Session>();
        tools = defaultTools();
        config = new Config();
    }

    // 默认工具集 = 引擎真实支持的 4 组；每项开关都真正门控 agent 可用工具
    private List<Tool> defaultTools() {
        List<Tool> l = new ArrayList<Tool>();
        l.add(new Tool("tool.file", "文件", "读 / 写 / 列目录 / 文件信息（沙箱路径校验）", true, "dev"));
        l.add(new Tool("tool.search", "查找 / 搜索", "按名找文件、在文件里搜关键词", true, "dev"));
        l.add(new Tool("tool.shell", "Shell 沙箱", "/system/bin/sh 白名单命令 + 超时", false, "dev"));
        l.add(new Tool("tool.http", "HTTP 抓取", "抓取网页正文（限大小）", false, "net"));
        return l;
    }

    // 存档用的纯数据壳（类名/字段名被 R8 keep 住，计算出的 serialVersionUID 因此跨版本稳定，老存档可继续读）
    public static class State implements Serializable {
        public List<Session> sessions;
        public List<Tool> tools;
        public Config config;
    }
}
