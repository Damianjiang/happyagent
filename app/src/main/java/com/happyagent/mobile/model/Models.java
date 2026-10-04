package com.happyagent.mobile.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

// 几个数据壳子，跟着 Activity 传 extra、落盘都要用，所以都实现 Serializable
public final class Models {

    private Models() {}

    public static class Session implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String title;
        public final String agent;
        public int status;   // 0 运行中 / 1 暂停 / 2 完成 / 3 失败
        public long updatedAt;
        public final List<Message> messages;
        // 本会话工作区标签（外部目录显示名；空=用内置会话沙箱，未绑外部目录）
        public String workspaceLabel;

        public Session(String id, String title, String agent, int status, long updatedAt, List<Message> messages) {
            this.id = id;
            this.title = title;
            this.agent = agent;
            this.status = status;
            this.updatedAt = updatedAt;
            this.messages = messages == null ? new ArrayList<Message>() : messages;
        }

        public String statusLabel() {
            switch (status) {
                case 0: return "运行中";
                case 1: return "已暂停";
                case 2: return "已完成";
                case 3: return "失败";
                default: return "空闲";
            }
        }
    }

    public static class Attachment implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String fileName;
        public final String mime;
        public final String path;
        public final long size;

        public Attachment(String fileName, String mime, String path, long size) {
            this.fileName = fileName;
            this.mime = mime;
            this.path = path;
            this.size = size;
        }

        public boolean isImage() {
            return mime != null && mime.startsWith("image/");
        }
    }

    public static class Message implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String role;   // user / assistant / system / tool
        public final String text;
        public final long ts;
        public final List<Attachment> attachments;   // 老存档无该字段 → null
        // Markdown 渲染缓存（transient：Java 序列化跳过，只在本进程 RecyclerView 复用里省一次重算）
        public transient CharSequence md;

        public Message(String role, String text, long ts) {
            this(role, text, ts, null);
        }

        public Message(String role, String text, long ts, List<Attachment> attachments) {
            this.role = role;
            this.text = text;
            this.ts = ts;
            this.attachments = attachments;
        }

        public List<Attachment> safeAttachments() {
            return attachments == null ? new ArrayList<Attachment>() : attachments;
        }
    }

    public static class Tool implements Serializable {
        static final long serialVersionUID = 1L;
        public final String id;
        public final String name;
        public final String desc;
        public final boolean enabled;
        public final String category;

        public Tool(String id, String name, String desc, boolean enabled, String category) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.enabled = enabled;
            this.category = category;
        }
    }

    public static class Config implements Serializable {
        static final long serialVersionUID = 1L;
        // 三家供应商 id，老存档没有该字段（反序列化时为 null），getProvider() 归一为 openai
        public static final String PROVIDER_OPENAI = "openai";
        public static final String PROVIDER_GOOGLE = "google";
        public static final String PROVIDER_ANTHROPIC = "anthropic";

        public String agentName = "Happy-Agent";
        public String model = "gpt-4o-mini";
        public int temperature = 70;     // 百分制；已固定默认 0.7（引擎不吃旧存档值），仅诊断页展示用
        public int maxTokens = 4096;
        public boolean autoCommit = true;
        public String workspace = "~/workspace";

        public String provider = PROVIDER_OPENAI;
        // 各供应商凭据，留空则降级本地模拟
        public String openaiKey = "";
        public String openaiBaseUrl = "https://api.openai.com/v1";
        public String googleKey = "";
        public String anthropicKey = "";
        // 任务最大步数（4~20）；老存档无该字段反序列化为 0，由 AgentBackend 归一为默认 10
        public int maxSteps = 10;

        public Config() {}

        public Config(String agentName, String model, int temperature,
                     int maxTokens, boolean autoCommit, String workspace) {
            this.agentName = agentName;
            this.model = model;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.autoCommit = autoCommit;
            this.workspace = workspace;
        }

        public Config(String agentName, String model, int temperature,
                     int maxTokens, boolean autoCommit, String workspace,
                     String openaiKey, String openaiBaseUrl) {
            this(agentName, model, temperature, maxTokens, autoCommit, workspace);
            this.openaiKey = openaiKey == null ? "" : openaiKey;
            this.openaiBaseUrl = openaiBaseUrl == null || openaiBaseUrl.isEmpty()
                    ? "https://api.openai.com/v1" : openaiBaseUrl;
        }

        // 保存配置页整包用：provider + 三家凭据一起存
        public Config(String agentName, String model, int temperature,
                     int maxTokens, boolean autoCommit, String workspace,
                     String provider,
                     String openaiKey, String openaiBaseUrl,
                     String googleKey, String anthropicKey) {
            this(agentName, model, temperature, maxTokens, autoCommit, workspace,
                    openaiKey, openaiBaseUrl);
            this.provider = (provider == null || provider.trim().isEmpty())
                    ? PROVIDER_OPENAI : provider;
            this.googleKey = googleKey == null ? "" : googleKey;
            this.anthropicKey = anthropicKey == null ? "" : anthropicKey;
        }

        public String getProvider() {
            return provider == null || provider.trim().isEmpty() ? PROVIDER_OPENAI : provider;
        }

        // 当前供应商对应的 Key，没配（或老存档为 null）返回空串
        public String apiKey() {
            String k;
            switch (getProvider()) {
                case PROVIDER_GOOGLE:   k = googleKey; break;
                case PROVIDER_ANTHROPIC: k = anthropicKey; break;
                default:                 k = openaiKey; break;
            }
            return k == null ? "" : k;
        }

        // 有当前供应商的 Key 才调真接口，否则本地模拟
        public boolean hasKey() {
            return apiKey().trim().length() > 0;
        }

        // 最大步数归一：老存档反序列化为 0 时回默认 10；越界收 4~20
        public int normalizedMaxSteps() {
            return maxSteps >= 4 && maxSteps <= 20 ? maxSteps : 10;
        }

        // 兼容旧调用点
        public boolean hasOpenAIKey() {
            return hasKey();
        }

        @Override
        public String toString() {
            String key = hasKey() ? "已配置" : "未配置";
            return "Agent: " + agentName + "\nProvider: " + getProvider()
                    + "\nModel: " + model
                    + "\nTemperature: " + (temperature / 100.0)
                    + "\nMax tokens: " + maxTokens
                    + "\nAuto commit: " + autoCommit
                    + "\nWorkspace: " + workspace
                    + "\nKey: " + key;
        }
    }
}
