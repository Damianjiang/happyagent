package com.happyagent.mobile.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

// 几个数据壳子，跟着 Activity 传 extra、落盘都要用，所以都实现 Serializable
public final class Models {

    private Models() {}

    public static class Session implements Serializable {
        public final String id;
        public final String title;
        public final String agent;
        public int status;   // 0 运行中 / 1 暂停 / 2 完成 / 3 失败
        public long updatedAt;
        public final List<Message> messages;

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

    public static class Message implements Serializable {
        public final String role;   // user / assistant / system / tool
        public final String text;
        public final long ts;

        public Message(String role, String text, long ts) {
            this.role = role;
            this.text = text;
            this.ts = ts;
        }
    }

    public static class Tool implements Serializable {
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
        public String agentName = "Agen-Primary";
        public String model = "agnes-3.0-flash";
        public int temperature = 0;      // 百分制，0~2000
        public int maxTokens = 4096;
        public boolean autoCommit = true;
        public String workspace = "~/.agnes";

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

        @Override
        public String toString() {
            return "Agent: " + agentName + "\nModel: " + model
                    + "\nTemperature: " + (temperature / 100.0)
                    + "\nMax tokens: " + maxTokens
                    + "\nAuto commit: " + autoCommit
                    + "\nWorkspace: " + workspace;
        }
    }
}
