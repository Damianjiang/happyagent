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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// 单例后端：会话、工具、配置都在这管
// 落盘读写全在单线程池里做，界面线程只拿快照，老机不卡
public final class AgentBackend {

    private static final String TAG = "AgentBackend";
    private static final long WAIT_STEP_MS = 50;

    private static volatile AgentBackend instance;

    private final File storeDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    private List<Session> sessions = new ArrayList<>();
    private List<Tool> tools = new ArrayList<>();
    private Config config = new Config();
    private volatile boolean loaded;

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

    // 应用在启动时调一次，后台把存档读出来
    public void preload() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    loadFromDisk();
                    seedIfEmpty();
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
            // 等太久了就当场同步读一遍，保证界面不卡死
            if (!loaded) {
                loadFromDisk();
                seedIfEmpty();
                loaded = true;
            }
        }
    }

    // ---- 会话 ----

    public List<Session> getSessions() {
        ensureLoaded();
        synchronized (lock) {
            return new ArrayList<>(sessions);
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

    // 跑一次任务：计划 -> 工具调用 -> 总结，全程离线
    public Future<Session> runTask(String sessionId, String prompt, String model) {
        ensureLoaded();
        final Session session = getSession(sessionId);
        if (session == null) throw new IllegalStateException("unknown session " + sessionId);

        synchronized (lock) {
            session.status = 0;
            session.updatedAt = System.currentTimeMillis();
        }

        return io.submit(new Callable<Session>() {
            @Override
            public Session call() {
                List<Message> trace = new ArrayList<Message>();
                trace.add(new Message("user", prompt, System.currentTimeMillis()));
                try {
                    String summary;
                    // 走多步 ReAct agent：有 OpenAI Key 时 LLM 驱动工具调用，没 Key 降级本地模拟
                    ReactAgent agent = new ReactAgent(getConfig(),
                            new FileTools(HappyAgentApplication.get()),
                            new ShellExecutor(HappyAgentApplication.get()),
                            trace);
                    summary = agent.run(prompt);
                    // 把轨迹里的关键节点也并进会话消息
                    for (Message m : trace) {
                        if (m.role.equals("system") || m.role.equals("tool")) {
                            session.messages.add(m);
                        }
                    }
                    synchronized (lock) {
                        session.messages.add(new Message("assistant", summary, System.currentTimeMillis()));
                        session.status = 2;
                        session.updatedAt = System.currentTimeMillis();
                    }
                    persistNow();
                    return session;
                } catch (Exception e) {
                    Log.e(TAG, "runTask failed", e);
                    synchronized (lock) {
                        session.status = 3;
                        session.messages.add(new Message("system", "Task failed: " + e.getMessage(), System.currentTimeMillis()));
                        session.updatedAt = System.currentTimeMillis();
                    }
                    persistNow();
                    return session;
                }
            }
        });
    }

    // ---- 工具 ----

    public List<Tool> getTools() {
        ensureLoaded();
        synchronized (lock) {
            return new ArrayList<>(tools);
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
                st.sessions = new ArrayList<>(sessions);
                st.tools = new ArrayList<>(tools);
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

    // 第一次装完没有数据，放一条欢迎会话
    private void seedIfEmpty() {
        if (!sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(new Message("assistant",
                "Hello! I'm your local agent. Create a session and run a task; "
                        + "I'll show a full plan -> tool -> summary trace.", now - 60000));
        sessions.add(new Session("s-seed-1", "欢迎使用HappyAgent", config.agentName, 2, now, msgs));
        persistNow();
    }

    private List<Tool> defaultTools() {
        List<Tool> l = new ArrayList<Tool>();
        l.add(new Tool("tool.context", "Context loader", "Load agent + workspace context", true, "core"));
        l.add(new Tool("tool.search", "Code search", "Search symbols and references", true, "dev"));
        l.add(new Tool("tool.file", "File ops", "Read / write / patch project files", true, "dev"));
        l.add(new Tool("tool.shell", "Shell", "Run commands in sandbox", false, "dev"));
        l.add(new Tool("tool.http", "HTTP", "Fetch web resources", false, "net"));
        l.add(new Tool("tool.memory", "Memory", "Inspect session history", true, "core"));
        return l;
    }

    // 存档用的纯数据壳
    public static class State implements Serializable {
        public List<Session> sessions;
        public List<Tool> tools;
        public Config config;
    }
}
