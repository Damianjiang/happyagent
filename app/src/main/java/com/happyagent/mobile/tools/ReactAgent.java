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

                // 宽容解析：tool_call / tool / tool_use / action 都能认；参数键名/工具名都不卡死
                if (parsed.has("tool_call") || parsed.has("tool") || parsed.has("tool_use")
                        || parsed.has("action")) {
                    JSONObject tc = firstPresent(parsed, "tool_call", "tool", "tool_use", "action");
                    if (tc == null) tc = parsed;
                    String rawName = firstNonEmpty(tc, "name", "tool", "tool_name", "action", "tool_call");
                    String toolName = normalizeToolName(rawName);
                    JSONObject args = normalizeArgs(tc);

                    // 不校验"必填"，直接宽容执行：缺的参数用默认值/别名填上，
                    // 结果里带提示信息，弱模型看结果也能自己修正
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

    // 模型输出五花八门：从候选键名里找第一个存在的（宽松匹配不同模型的字段命名）
    private JSONObject firstPresent(JSONObject o, String... keys) {
        for (String k : keys) {
            JSONObject v = o.optJSONObject(k);
            if (v != null) return v;
        }
        return null;
    }

    private String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (v.length() > 0) return v;
        }
        return "";
    }

    // 工具名宽容匹配：别名/大小写/下划线都能归一到标准工具名
    private String normalizeToolName(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        // 精确别名表（英文标准 + 常见同义词）
        String[][] aliasMap = {
                {"file_read", "read", "read_file", "cat", "open", "view", "show_file", "读取文件"},
                {"file_write", "write", "write_file", "save", "create_file", "写入", "创建文件"},
                {"file_list", "list", "ls", "list_dir", "list_files", "dir", "列目录", "看目录"},
                {"file_find", "find", "find_file", "查找", "搜文件"},
                {"file_grep", "grep", "search", "search_file", "search_files", "搜索", "搜代码"},
                {"file_info", "info", "stat", "文件信息"},
                {"shell_exec", "shell", "exec", "execute", "run", "run_shell", "执行命令", "跑命令", "执行"},
                {"shell_detect", "detect", "detect_proot", "探测"},
                {"http_get", "http", "fetch", "download", "get_url", "web", "抓网页", "下载"},
                {"memory_recall", "memory", "recall", "记忆"},
        };
        for (String[] group : aliasMap) {
            for (String alias : group) {
                if (t.equals(alias.toLowerCase()) || t.equals(alias)) return group[0];
            }
            // 包含匹配：弱模型可能写 "read the file x.txt" / "读取文件"，任一别名命中就归一
            for (String alias : group) {
                if (alias.length() >= 4 && (t.contains(alias.toLowerCase()) || t.contains(alias))) {
                    return group[0];
                }
            }
        }
        return raw;   // 认不出来原样传，dispatchTool 返回可行动的提示
    }

    // 参数键名宽容：arguments/args/params 都认（对象或 JSON 字符串）；path/file/dir/url 归一
    private JSONObject normalizeArgs(JSONObject tc) {
        JSONObject args = null;
        String[] argKeys = {"arguments", "args", "parameters", "params", "input"};
        for (int i = 0; i < argKeys.length && args == null; i++) {
            JSONObject jo = tc.optJSONObject(argKeys[i]);
            if (jo != null) {
                args = jo;
            } else {
                String s = tc.optString(argKeys[i], "").trim();
                if (s.length() > 0) {
                    try { args = new JSONObject(s); }
                    catch (Exception ignored) { args = new JSONObject(); }
                }
            }
        }
        if (args == null) {
            // 模型把参数直接摊在顶层（name 和 path 平级），收进 args
            args = new JSONObject();
            try {
                JSONArray keys = tc.names();
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.getString(i);
                    if (!k.equals("name") && !k.equals("tool") && !k.equals("action")
                            && !k.equals("tool_call") && !k.equals("id") && !k.equals("thought")
                            && !k.equals("reasoning")) {
                        args.put(k, tc.opt(k));
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            // path 类键名归一：弱模型写 file/dir/url 都能落到 path
            String path = firstNonEmpty(args, "path", "file", "dir", "directory",
                    "file_path", "filename", "url");
            if (path.length() > 0) args.put("path", path);
        } catch (Exception ignored) {}
        return args;
    }

    private List<Message> recentWindow(List<Message> all, int n) {
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    // 工具分发。参数宽容：缺 path 用工作区默认，缺参数时返回"可行动的提示"而不是干失败，
    // 弱模型看到提示下一步能自己补齐（比直接踢回缺参重试更省步数）
    private String dispatchTool(String name, JSONObject args) {
        try {
            switch (name) {
                case "file_read": {
                    String p = str(args, "path", "");
                    if (p.isEmpty()) {
                        return "Missing 'path'. Files I can read right now:\n" + fileTools.list(fileTools.getWorkspace());
                    }
                    return fileTools.read(p);
                }
                case "file_write": {
                    String p = str(args, "path", "");
                    if (p.isEmpty()) {
                        return "Missing 'path'. Pick a target under: " + fileTools.getWorkspace()
                                + " (e.g. \"notes.txt\"), then retry with {\"path\":...,\"content\":...}.";
                    }
                    return fileTools.write(p, str(args, "content", str(args, "text", "")));
                }
                case "file_list":
                    return fileTools.list(str(args, "path", fileTools.getWorkspace()));
                case "file_find": {
                    String p = str(args, "path", fileTools.getWorkspace());
                    String n = str(args, "name", str(args, "query", str(args, "keyword", "")));
                    if (n.isEmpty()) {
                        return "Missing 'name'. Files available:\n" + fileTools.list(p);
                    }
                    return fileTools.find(p, n);
                }
                case "file_grep": {
                    String p = str(args, "path", "");
                    String k = str(args, "keyword", str(args, "query", ""));
                    if (p.isEmpty() || k.isEmpty()) {
                        return "Need 'path' and 'keyword'. Files available:\n" + fileTools.list(fileTools.getWorkspace());
                    }
                    return fileTools.grep(p, k);
                }
                case "file_info":
                    return fileTools.info(str(args, "path", fileTools.getWorkspace()));
                case "shell_exec": {
                    String c = str(args, "command", str(args, "cmd", ""));
                    if (c.isEmpty()) {
                        return "Missing 'command'. Allowed commands: "
                                + "ls cat echo date uname whoami pwd id grep head tail wc stat find which";
                    }
                    int timeout = args.has("timeout_ms") ? args.optInt("timeout_ms", 15000) : 15000;
                    return shell.exec(c, timeout);
                }
                case "shell_detect":
                    return shell.detectProot();
                case "http_get": {
                    String u = str(args, "url", str(args, "path", ""));
                    if (u.isEmpty()) {
                        return "Missing 'url'. Fetch a page like {\"url\":\"https://example.com\"}.";
                    }
                    return httpGet(u);
                }
                case "memory_recall":
                    return "workspace: " + fileTools.getWorkspace();
                default:
                    return "Unknown tool '" + name + "'. Available tools: "
                            + "file_read, file_write, file_list, file_find, file_grep, file_info, "
                            + "shell_exec, shell_detect, http_get, memory_recall. Pick one and retry.";
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
        return "You are an AI agent on an Android device. Use tools to complete the task. "
                + "To call a tool reply JSON: {\"tool_call\":{\"name\":\"<tool>\",\"arguments\":{...}}} "
                + "or finish with {\"answer\":\"<final>\"}.\n"
                + "You may omit optional arguments. If you miss a required one, the tool result will "
                + "tell you exactly what to provide — read it and retry; do not repeat the same empty call.\n"
                + "Example: read a file -> {\"tool_call\":{\"name\":\"file_read\",\"arguments\":{\"path\":\"notes.txt\"}}}\n"
                + "Tools:\n"
                + "- file_read {path} — read a text file\n"
                + "- file_write {path, content} — create/overwrite a file\n"
                + "- file_list {path} — list directory (default: workspace)\n"
                + "- file_find {path, name} — find files by name\n"
                + "- file_grep {path, keyword} — search text in a file\n"
                + "- file_info {path} — file size/type\n"
                + "- shell_exec {command} — run one of: ls cat echo date uname whoami pwd grep find\n"
                + "- http_get {url} — fetch a web page\n"
                + "- memory_recall {} — show workspace info\n"
                + "Workspace: " + fileTools.getWorkspace() + "\n"
                + "Up to " + MAX_STEPS + " steps. Keep answers concise.";
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
