package com.happyagent.mobile.tools;

import com.happyagent.mobile.model.Models.Attachment;
import com.happyagent.mobile.model.Models.Config;
import com.happyagent.mobile.model.Models.Message;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// 多步 ReAct agent：think → act(tool) → observe → ... → answer（对应 Operit PhoneAgent）
// 有 OpenAI Key 时 LLM 带多轮历史驱动工具调用，没 Key 降级本地模拟。
// 工具调用带参数校验：缺必填参数不崩，把"缺哪些"作为反馈喂回模型补齐（对齐 Operit ToolPackage）。
public final class ReactAgent {

    private static final int MAX_STEPS = 10;
    private static final int HISTORY_WINDOW = 12;   // 只喂最近 12 条 user/assistant，治聊天卡
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;

    private final Config cfg;
    private final FileTools fileTools;
    private final ShellExecutor shell;
    private final List<Message> trace;
    private final TaskControl control;
    // 本轮任务携带的图片附件，多模态喂模型用
    private List<Attachment> currentImages;

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, List<Message> trace) {
        this(cfg, ft, se, trace, new TaskControl());
    }

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, List<Message> trace, TaskControl control) {
        this.cfg = cfg;
        this.fileTools = ft;
        this.shell = se;
        this.trace = trace;
        this.control = control;
    }

    public String run(String task, List<Message> history, List<Attachment> attachments) throws Exception {
        this.currentImages = attachments == null ? new ArrayList<Attachment>() : attachments;
        String systemPrompt = buildSystemPrompt();
        trace.add(new Message("system", "PLAN: " + task, System.currentTimeMillis()));

        if (!cfg.hasKey()) {
            return localFallback(task, history, currentImages);
        }

        // 只喂最近 HISTORY_WINDOW 条 user/assistant，控制上下文长度，避免聊多了卡
        List<Message> window = recentWindow(history, HISTORY_WINDOW);
        List<JSONObject> messages = new ArrayList<JSONObject>();
        addMsg(messages, "system", systemPrompt);
        for (Message h : window) {
            addMsg(messages, h.role, h.text);
        }
        // 当前任务：文本 + 本轮图片一起送（多模态）
        addUserTask(messages, task);

        for (int step = 1; step <= MAX_STEPS; step++) {
            // 每步自查控制：取消/线程被打断则停；暂停则阻塞等待继续
            if (control.shouldStop()) return cancelled();
            control.waitForResume();
            if (control.shouldStop()) return cancelled();

            trace.add(new Message("system", "STEP " + step + " / " + MAX_STEPS, System.currentTimeMillis()));
            try {
                String llmResponse = callLlm(messages);
                JSONObject parsed = parseLlmResponse(llmResponse);

                if (parsed.has("tool_call")) {
                    JSONObject tc = parsed.getJSONObject("tool_call");
                    String toolName = tc.optString("name", "");
                    JSONObject args = tc.has("arguments") ? tc.getJSONObject("arguments") : new JSONObject();

                    // 参数校验：缺必填且无默认 → 不崩，把缺的喂回模型补齐（Operit 的做法）
                    String missing = missingRequired(toolName, args);
                    if (missing != null) {
                        addMsg(messages, "assistant", llmResponse);
                        addMsg(messages, "user",
                                "Tool [" + toolName + "] is missing required parameter(s): "
                                        + missing + ". Please provide all required parameters and call again.");
                        trace.add(new Message("tool", "missing params: " + missing, System.currentTimeMillis()));
                        continue;   // 下一步让模型补参数
                    }

                    String result = dispatchTool(toolName, args);
                    trace.add(new Message("tool", toolName + " -> " + result, System.currentTimeMillis()));
                    addMsg(messages, "assistant", llmResponse);
                    addMsg(messages, "user", "Tool [" + toolName + "] returned: " + result + ". Continue.");
                } else {
                    String answer = parsed.optString("answer", llmResponse);
                    trace.add(new Message("assistant", answer, System.currentTimeMillis()));
                    return answer;
                }
            } catch (Exception e) {
                // 用户取消导致的连接中断按"已停止"正常返回；真正的 LLM/网络错误往上抛，
                // 由后端记为任务失败（status 3），而不是当成"成功完成"
                if (control.isCancelled()) {
                    return cancelled();
                }
                trace.add(new Message("system", "Step " + step + " error: " + e.getMessage(),
                        System.currentTimeMillis()));
                throw new Exception("Step " + step + " failed: " + e.getMessage(), e);
            }
        }
        return "Reached max steps (" + MAX_STEPS + ").";
    }

    // 取消/被打断的统一收尾：记录一条停止原因，返回占位文案
    private String cancelled() {
        trace.add(new Message("system", "Agent stopped by user", System.currentTimeMillis()));
        return "任务已停止";
    }

    // 每个工具的必填参数 + 默认值。缺必填且无默认 → 返回缺失名单；否则 null（可执行）
    private String missingRequired(String tool, JSONObject args) {
        switch (tool) {
            case "file_read":
            case "file_info":
            case "file_grep":
                return need(args, "path", null);
            case "file_write":
                if (need(args, "path", null) != null) return "path";
                return need(args, "content", "");
            case "file_list":
                return need(args, "path", fileTools.getWorkspace());
            case "file_find":
                if (need(args, "path", fileTools.getWorkspace()) != null) return "path";
                return need(args, "name", null);
            case "shell_exec":
                return need(args, "command", null);
            case "http_get":
                return need(args, "url", null);
            case "shell_detect":
            case "memory_recall":
                return null;
            default:
                return "unknown tool " + tool;
        }
    }

    private String need(JSONObject args, String key, String def) {
        if (args.has(key)) {
            String v = args.optString(key, "").trim();
            if (v.length() > 0) return null;
        }
        if (def != null) return null;   // 有默认值，不记缺失
        return key;
    }

    private List<Message> recentWindow(List<Message> all, int n) {
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    // 工具分发。args 已校验，缺值用默认
    private String dispatchTool(String name, JSONObject args) {
        try {
            switch (name) {
                case "file_read":
                    return fileTools.read(str(args, "path", ""));
                case "file_write":
                    return fileTools.write(str(args, "path", ""), str(args, "content", ""));
                case "file_list":
                    return fileTools.list(str(args, "path", fileTools.getWorkspace()));
                case "file_find":
                    return fileTools.find(str(args, "path", fileTools.getWorkspace()), str(args, "name", ""));
                case "file_grep":
                    return fileTools.grep(str(args, "path", ""), str(args, "keyword", ""));
                case "file_info":
                    return fileTools.info(str(args, "path", fileTools.getWorkspace()));
                case "shell_exec":
                    int timeout = args.has("timeout_ms") ? args.optInt("timeout_ms", 15000) : 15000;
                    return shell.exec(str(args, "command", ""), timeout);
                case "shell_detect":
                    return shell.detectProot();
                case "http_get":
                    return httpGet(str(args, "url", ""));
                case "memory_recall":
                    return "workspace: " + fileTools.getWorkspace();
                default:
                    return "unknown tool: " + name;
            }
        } catch (Exception e) {
            return "tool error: " + e.getMessage();
        }
    }

    private String str(JSONObject o, String key, String def) {
        String v = o.optString(key, "");
        return v.isEmpty() ? def : v;
    }

    private String buildSystemPrompt() {
        return "You are an AI agent on an Android device. Complete tasks using tools. "
                + "Respond as JSON: {\"tool_call\":{\"name\":\"<tool>\",\"arguments\":{...}}} to call a tool, "
                + "or {\"answer\":\"<final>\"} to finish. Always provide ALL required arguments.\n"
                + "Tools:\n"
                + "- file_read {path}\n"
                + "- file_write {path, content}\n"
                + "- file_list {path}\n"
                + "- file_find {path, name}\n"
                + "- file_grep {path, keyword}\n"
                + "- file_info {path}\n"
                + "- shell_exec {command, timeout_ms}\n"
                + "- shell_detect {}\n"
                + "- http_get {url}\n"
                + "- memory_recall {}\n"
                + "Workspace: " + fileTools.getWorkspace() + "\n"
                + "Up to " + MAX_STEPS + " steps. Think step by step.";
    }

    // 按供应商适配请求/响应格式
    private String callLlm(List<JSONObject> messages) throws Exception {
        String provider = cfg.getProvider();
        if (Config.PROVIDER_GOOGLE.equals(provider)) return callGoogle(messages);
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) return callAnthropic(messages);
        return callOpenAI(messages);
    }

    private String callOpenAI(List<JSONObject> messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", cfg.temperature / 100.0);
        body.put("max_tokens", cfg.maxTokens);
        JSONArray arr = new JSONArray();
        for (JSONObject m : messages) {
            JSONObject copy = new JSONObject(m.toString());
            JSONArray imgs = imagesOf(copy);
            copy.remove("__images__");
            if (imgs != null && imgs.length() > 0) {
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", copy.optString("content", "")));
                for (int i = 0; i < imgs.length(); i++) {
                    JSONObject im = imgs.getJSONObject(i);
                    String dataUri = "data:" + im.optString("mime", "image/png")
                            + ";base64," + im.optString("data", "");
                    JSONObject part = new JSONObject();
                    part.put("type", "image_url");
                    part.put("image_url", new JSONObject().put("url", dataUri));
                    content.put(part);
                }
                copy.put("content", content);
            }
            arr.put(copy);
        }
        body.put("messages", arr);

        String base = cfg.openaiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";

        HttpURLConnection conn = connPost(url, body.toString());
        conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey().trim());
        String resp = post(conn, url, body.toString());
        JSONObject jo = new JSONObject(resp);
        JSONArray choices = jo.getJSONArray("choices");
        return choices.getJSONObject(0).getJSONObject("message").getString("content");
    }

    private String callGoogle(List<JSONObject> messages) throws Exception {
        // 把对话压成 contents，system 归到 systemInstruction
        StringBuilder sys = new StringBuilder();
        JSONArray contents = new JSONArray();
        for (JSONObject m : messages) {
            String role = m.optString("role");
            String text = m.optString("content");
            JSONArray imgs = imagesOf(m);
            if ("system".equals(role)) {
                if (sys.length() > 0) sys.append("\n");
                sys.append(text);
            } else {
                JSONArray parts = new JSONArray();
                if (text != null && text.length() > 0) {
                    parts.put(new JSONObject().put("text", text));
                }
                if (imgs != null) {
                    for (int i = 0; i < imgs.length(); i++) {
                        JSONObject im = imgs.getJSONObject(i);
                        JSONObject inline = new JSONObject();
                        inline.put("mime_type", im.optString("mime", "image/png"));
                        inline.put("data", im.optString("data", ""));
                        parts.put(new JSONObject().put("inline_data", inline));
                    }
                }
                JSONObject c = new JSONObject();
                c.put("role", "user".equals(role) ? "user" : "model");
                c.put("parts", parts);
                contents.put(c);
            }
        }
        JSONObject body = new JSONObject();
        body.put("contents", contents);
        body.put("temperature", cfg.temperature / 100.0);
        body.put("maxOutputTokens", cfg.maxTokens);
        if (sys.length() > 0) {
            body.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", sys.toString()))));
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + cfg.model + ":generateContent";
        HttpURLConnection conn = connPost(url, body.toString());
        conn.setRequestProperty("x-goog-api-key", cfg.apiKey().trim());
        String resp = post(conn, url, body.toString());
        JSONObject jo = new JSONObject(resp);
        JSONArray cand = jo.getJSONArray("candidates");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cand.length(); i++) {
            JSONArray parts = cand.getJSONObject(i).getJSONObject("content").getJSONArray("parts");
            for (int j = 0; j < parts.length(); j++) {
                out.append(parts.getJSONObject(j).optString("text", ""));
            }
        }
        return out.length() > 0 ? out.toString() : "";
    }

    private String callAnthropic(List<JSONObject> messages) throws Exception {
        StringBuilder sys = new StringBuilder();
        JSONArray arr = new JSONArray();
        for (JSONObject m : messages) {
            String role = m.optString("role");
            String text = m.optString("content");
            JSONArray imgs = imagesOf(m);
            if ("system".equals(role)) {
                if (sys.length() > 0) sys.append("\n");
                sys.append(text);
            } else if ("tool".equals(role)) {
                JSONObject o = new JSONObject();
                o.put("role", "user");
                o.put("content", "[tool result] " + text);
                arr.put(o);
            } else {
                JSONObject o = new JSONObject();
                o.put("role", "user".equals(role) ? "user" : "assistant");
                if (imgs != null && imgs.length() > 0) {
                    JSONArray content = new JSONArray();
                    if (text != null && text.length() > 0) {
                        content.put(new JSONObject().put("type", "text").put("text", text));
                    }
                    for (int i = 0; i < imgs.length(); i++) {
                        JSONObject im = imgs.getJSONObject(i);
                        JSONObject src = new JSONObject();
                        src.put("type", "base64");
                        src.put("media_type", im.optString("mime", "image/png"));
                        src.put("data", im.optString("data", ""));
                        content.put(new JSONObject().put("type", "image")
                                .put("source", src));
                    }
                    o.put("content", content);
                } else {
                    o.put("content", text);
                }
                arr.put(o);
            }
        }
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("max_tokens", cfg.maxTokens);
        body.put("temperature", Math.min(1.0, cfg.temperature / 100.0));
        body.put("messages", arr);
        if (sys.length() > 0) body.put("system", sys.toString());

        String url = "https://api.anthropic.com/v1/messages";
        HttpURLConnection conn = connPost(url, body.toString());
        conn.setRequestProperty("x-api-key", cfg.apiKey().trim());
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        String resp = post(conn, url, body.toString());
        JSONObject jo = new JSONObject(resp);
        JSONArray blocks = jo.getJSONArray("content");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            out.append(blocks.getJSONObject(i).optString("text", ""));
        }
        return out.length() > 0 ? out.toString() : "";
    }

    private HttpURLConnection connPost(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/json");
        control.setConn(conn);
        return conn;
    }

    // 真正写请求体 + 读响应体。读是阻塞点，取消时由 TaskControl.cancel 断连接让这里抛异常
    private String post(HttpURLConnection conn, String url, String body) throws Exception {
        try {
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
            is.close();
            conn.disconnect();
            String resp = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + resp);
            return resp;
        } finally {
            control.clearConn();
        }
    }

    private void addMsg(List<JSONObject> msgs, String role, String content) {
        try {
            JSONObject m = new JSONObject();
            m.put("role", role);
            m.put("content", content);
            msgs.add(m);
        } catch (Exception ignored) {}
    }

    // 当前任务的用户消息：文本 + 图片（图片存成 data URI 标记，各家展开时读）
    private void addUserTask(List<JSONObject> msgs, String task) {
        try {
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", task);
            JSONArray imgs = new JSONArray();
            for (Attachment a : currentImages) {
                if (!a.isImage()) continue;
                String b64 = readImageBase64(a.path);
                if (b64 == null) continue;
                JSONObject im = new JSONObject();
                im.put("mime", a.mime);
                im.put("data", b64);
                im.put("name", a.fileName);
                imgs.put(im);
            }
            m.put("__images__", imgs);
            msgs.add(m);
        } catch (Exception ignored) {}
    }

    private String readImageBase64(String path) {
        if (path == null) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            byte[] buf = new byte[65536];
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n;
            while ((n = fis.read(buf)) != -1) bo.write(buf, 0, n);
            byte[] bytes = bo.toByteArray();
            fis.close();
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray imagesOf(JSONObject m) {
        return m.optJSONArray("__images__");
    }

    private JSONObject parseLlmResponse(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return new JSONObject(raw.substring(start, end + 1));
                } catch (Exception e2) {
                    JSONObject o = new JSONObject();
                    try { o.put("answer", raw.trim()); } catch (Exception ignored) {}
                    return o;
                }
            }
            JSONObject o = new JSONObject();
            try { o.put("answer", raw.trim()); } catch (Exception ignored) {}
            return o;
        }
    }

    private String localFallback(String task, List<Message> history, List<Attachment> images) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n");
        sb.append("Local engine (no API key configured)\n");
        sb.append("Rounds so far: ").append(history.size()).append("\n");
        if (images != null && !images.isEmpty()) {
            sb.append("Attachments: ").append(images.size())
              .append(" file(s) received: ");
            for (Attachment a : images) sb.append(a.fileName).append(' ');
        }
        sb.append("\nReply: 收到「").append(task)
          .append("」。离线本地模式，已收到附件存到工作区；在配置页填 ")
          .append(providerLabel()).append(" 的 API Key 后我会真正理解并回答你。");
        return sb.toString();
    }

    private String providerLabel() {
        switch (cfg.getProvider()) {
            case Config.PROVIDER_GOOGLE:   return "Google";
            case Config.PROVIDER_ANTHROPIC: return "Anthropic";
            default:                        return "OpenAI";
        }
    }

    private String httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1 && bo.size() < 65536) bo.write(buf, 0, n);
            is.close();
            conn.disconnect();
            String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            return "HTTP " + code + ": " + body.substring(0, Math.min(4096, body.length()));
        } catch (Exception e) {
            return "http error: " + e.getMessage();
        }
    }
}
