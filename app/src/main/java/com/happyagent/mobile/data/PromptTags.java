package com.happyagent.mobile.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// 标签 / 提示词片段：多段可挂、启用段拼接进系统提示词，JSON 存 Prefs。
// 每段 {name, content, enabled}。API 23 安全（org.json，无 List.of/stream/Optional）。
public final class PromptTags {

    public static final class Tag {
        public final String name;
        public final String content;
        public boolean enabled;

        public Tag(String name, String content, boolean enabled) {
            this.name = name == null ? "" : name;
            this.content = content == null ? "" : content;
            this.enabled = enabled;
        }
    }

    private PromptTags() {}

    // 预设库：点一下即加入"已建"并默认启用
    public static List<Tag> presets() {
        List<Tag> l = new ArrayList<Tag>();
        l.add(new Tag("简洁", "回答保持简短，要点式，不啰嗦。", true));
        l.add(new Tag("代码可运行", "给代码时必须完整可运行，带边界处理与示例；不贴半成品。", true));
        l.add(new Tag("先确认再操作", "涉及删除/发送/金钱/覆盖等不可逆操作前，必须先跟我确认再执行。", true));
        l.add(new Tag("中文优先", "默认用中文回答；代码与术语保留原文。", true));
        l.add(new Tag("给依据", "下结论时给出依据或出处；不确定就明说，不臆造。", true));
        return l;
    }

    // 从 Prefs 的 JSON 串解析成 Tag 列表（空/坏数据返回空表，不抛）
    public static List<Tag> load(String json) {
        List<Tag> out = new ArrayList<Tag>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Tag(o.optString("name", ""), o.optString("content", ""),
                        o.optBoolean("enabled", false)));
            }
        } catch (Exception e) {
            // 坏数据不崩：返回空表
        }
        return out;
    }

    // 序列化成 JSON 存 Prefs（坏数据不抛）
    public static String save(List<Tag> tags) {
        JSONArray arr = new JSONArray();
        for (Tag t : tags) {
            try {
                arr.put(new JSONObject()
                        .put("name", t.name)
                        .put("content", t.content)
                        .put("enabled", t.enabled));
            } catch (Exception e) {
                // 单条坏数据跳过，不整体崩
            }
        }
        return arr.toString();
    }

    // 把启用段拼成注入系统提示词的正文（每段一段，含名称引导）；全停用返回空串
    public static String enabledText(List<Tag> tags) {
        StringBuilder sb = new StringBuilder();
        for (Tag t : tags) {
            if (!t.enabled) continue;
            String c = t.content == null ? "" : t.content.trim();
            if (c.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            String n = t.name == null ? "" : t.name.trim();
            sb.append("〔").append(n.isEmpty() ? "标签" : n).append("〕 ").append(c);
        }
        return sb.toString().trim();
    }
}
