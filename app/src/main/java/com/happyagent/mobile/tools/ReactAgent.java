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
import java.util.Set;

// 多步 ReAct：思考→调工具→观察→继续，最多 MAX_STEPS 步。
// 有 Key 走原生 function calling，没 Key 走本地引擎；工具参数按别名宽容取值。
public final class ReactAgent {

    private static final int MAX_STEPS = 10;
    private static final int HISTORY_WINDOW = 12;
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;

    private final Config cfg;
    private final FileTools fileTools;
    private final ShellExecutor shell;
    private final SystemTools systemTools;
    private final List<Message> trace;
    private final TaskControl control;
    // 启用的工具分组 key（来自工具页开关，如 tool.file/tool.search/tool.shell/tool.http/tool.text/tool.system）。
    // null 表示全部启用（离线/未接工具页的场景）。
    private final Set<String> enabledTools;
    private List<Attachment> currentImages;
    private int callCounter;

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, SystemTools st, List<Message> trace) {
        this(cfg, ft, se, st, trace, new TaskControl(), null);
    }

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, SystemTools st, List<Message> trace, TaskControl control) {
        this(cfg, ft, se, st, trace, control, null);
    }

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, SystemTools st,
                      List<Message> trace, TaskControl control, Set<String> enabledTools) {
        this.cfg = cfg;
        this.fileTools = ft;
        this.shell = se;
        this.systemTools = st;
        this.trace = trace;
        this.control = control;
        this.enabledTools = enabledTools;
    }

    // 工具分组是否启用；null 视为全开
    private boolean toolOn(String groupKey) {
        return enabledTools == null || enabledTools.contains(groupKey);
    }

    public String run(String task, List<Message> history, List<Attachment> attachments) throws Exception {
        this.currentImages = attachments == null ? new ArrayList<Attachment>() : attachments;
        trace.add(new Message("system", "PLAN: " + task, System.currentTimeMillis()));

        if (!cfg.hasKey()) {
            return localFallback(task, history, currentImages);
        }

        // 供应商无关的记录；各 provider 的 callLlm 每次从 transcript 重建请求
        List<JSONObject> transcript = new ArrayList<JSONObject>();
        for (Message h : recentWindow(history, HISTORY_WINDOW)) {
            transcript.add(new JSONObject().put("role", h.role).put("text", h.text));
        }
        JSONObject userStep = new JSONObject().put("role", "user").put("text", task);
        userStep.put("images", imagePayloads());
        transcript.add(userStep);

        for (int step = 1; step <= MAX_STEPS; step++) {
            if (control.shouldStop()) return cancelled();
            control.waitForResume();
            if (control.shouldStop()) return cancelled();

            trace.add(new Message("system", "STEP " + step + " / " + MAX_STEPS, System.currentTimeMillis()));
            try {
                JSONObject r = callLlm(transcript);
                JSONArray calls = r.optJSONArray("calls");
                if (calls != null && calls.length() > 0) {
                    // OpenAI tool 消息需按 id 对应，补齐缺失的 id
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        if (c.optString("id", "").length() == 0) {
                            calls.put(i, new JSONObject(c.toString()).put("id", "call_" + step + "_" + i));
                        }
                    }
                    JSONArray results = new JSONArray();
                    JSONObject asst = new JSONObject().put("role", "assistant").put("calls", calls);
                    transcript.add(asst);
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        String toolName = normalizeToolName(c.optString("name", ""));
                        JSONObject args = c.optJSONObject("args");
                        if (args == null) args = new JSONObject();
                        String result = dispatchTool(toolName, args);
                        results.put(new JSONObject()
                                .put("id", c.optString("id", ""))
                                .put("name", toolName)
                                .put("result", result));
                        trace.add(new Message("tool", toolName + " -> " + result, System.currentTimeMillis()));
                    }
                    transcript.add(new JSONObject().put("role", "tool").put("results", results));
                    continue;
                }
                String answer = r.optString("answer", "");
                if (answer.isEmpty()) answer = "（模型本轮未返回内容，任务结束）";
                transcript.add(new JSONObject().put("role", "assistant").put("text", answer));
                trace.add(new Message("assistant", answer, System.currentTimeMillis()));
                return answer;
            } catch (Exception e) {
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

    private String cancelled() {
        trace.add(new Message("system", "Agent stopped by user", System.currentTimeMillis()));
        return "任务已停止";
    }

    private JSONArray imagePayloads() throws Exception {
        JSONArray imgs = new JSONArray();
        for (Attachment a : currentImages) {
            if (!a.isImage()) continue;
            String b64 = readImageBase64(a.path);
            if (b64 == null) continue;
            imgs.put(new JSONObject().put("mime", a.mime).put("data", b64).put("name", a.fileName));
        }
        return imgs;
    }

    // 三家均走原生 function calling，统一返回 {calls:[{id,name,args}], answer}
    private JSONObject callLlm(List<JSONObject> transcript) throws Exception {
        String provider = cfg.getProvider();
        if (Config.PROVIDER_GOOGLE.equals(provider)) return callGoogle(transcript);
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) return callAnthropic(transcript);
        return callOpenAI(transcript);
    }

    // OpenAI 工具 schema；只放工具页开关打开的分组
    private JSONArray openAITools() throws Exception {
        JSONArray arr = new JSONArray();
        if (toolOn("tool.file")) {
            arr.put(fn("file_read", "读取文本文件", schema("path", true)));
            arr.put(fn("file_write", "创建或覆盖文件", schema2("path", true, "content", true)));
            arr.put(fn("file_list", "列目录", schema("path", false)));
            arr.put(fn("file_info", "文件信息", schema("path", false)));
            arr.put(fn("file_exists", "检查文件/目录是否存在", schema("path", true)));
            arr.put(fn("file_move", "移动或重命名文件/目录", schema2("path", true, "to", true)));
            arr.put(fn("file_copy", "复制文件/目录", schema2("path", true, "to", true)));
            arr.put(fn("file_zip", "把文件/目录压缩成 zip", schema2("path", true, "to", true)));
            arr.put(fn("file_unzip", "把 zip 解压到目录", schema2("path", true, "to", true)));
            if (toolOn("tool.search")) {
                arr.put(fn("file_find", "按名查找文件", schema2("path", false, "name", true)));
                arr.put(fn("file_grep", "在文件里搜关键词", schema2("path", true, "keyword", true)));
            }
        }
        if (toolOn("tool.shell")) {
            arr.put(fn("shell_exec", "执行白名单命令", schema("command", true)));
        }
        if (toolOn("tool.http")) {
            arr.put(fn("http_get", "抓取网页", schema("url", true)));
        }
        if (toolOn("tool.text")) {
            arr.put(fn("text_base64_encode", "文本转 base64", schema("text", true)));
            arr.put(fn("text_base64_decode", "base64 转文本", schema("text", true)));
            arr.put(fn("text_url_encode", "URL 编码", schema("text", true)));
            arr.put(fn("text_url_decode", "URL 解码", schema("text", true)));
            arr.put(fn("text_json_get", "从 JSON 按点路径取字段", schema2("json", true, "path", false)));
            arr.put(fn("text_upper", "转大写", schema("text", true)));
            arr.put(fn("text_lower", "转小写", schema("text", true)));
            arr.put(fn("text_stats", "统计字符/行/词", schema("text", true)));
        }
        if (toolOn("tool.system")) {
            arr.put(fn("system_device_info", "设备概要(RAM/存储/网络/时间)", schema0()));
            arr.put(fn("system_battery", "电池电量与充电状态", schema0()));
            arr.put(fn("system_storage", "存储剩余/总量", schema0()));
            arr.put(fn("system_network", "网络类型与连接状态", schema0()));
            arr.put(fn("system_clipboard_get", "读剪贴板文本", schema0()));
            arr.put(fn("system_clipboard_set", "写剪贴板文本", schema("text", true)));
        }
        // 时间工具始终可用（无副作用）
        arr.put(fn("time_now", "获取当前日期时间", schema0()));
        return arr;
    }

    private JSONObject fn(String name, String desc, JSONObject params) throws Exception {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (params != null) f.put("parameters", params);
        return new JSONObject().put("type", "function").put("function", f);
    }

    private JSONObject schema0() throws Exception {
        JSONObject o = new JSONObject().put("type", "object").put("properties", new JSONObject());
        return o;
    }

    private JSONObject schema(String p, boolean req) throws Exception {
        JSONObject props = new JSONObject().put(p, new JSONObject().put("type", "string").put("description", p));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props);
        if (req) o.put("required", new JSONArray().put(p));
        return o;
    }

    private JSONObject schema2(String p1, boolean r1, String p2, boolean r2) throws Exception {
        JSONObject props = new JSONObject();
        props.put(p1, new JSONObject().put("type", "string").put("description", p1));
        props.put(p2, new JSONObject().put("type", "string").put("description", p2));
        JSONArray req = new JSONArray();
        if (r1) req.put(p1);
        if (r2) req.put(p2);
        JSONObject o = new JSONObject().put("type", "object").put("properties", props);
        if (req.length() > 0) o.put("required", req);
        return o;
    }

    private JSONObject callOpenAI(List<JSONObject> transcript) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
        for (JSONObject step : transcript) {
            String role = step.optString("role");
            if ("user".equals(role)) {
                JSONArray imgs = step.optJSONArray("images");
                String text = step.optString("text", "");
                if (imgs != null && imgs.length() > 0) {
                    JSONArray content = new JSONArray();
                    if (text.length() > 0) content.put(new JSONObject().put("type", "text").put("text", text));
                    for (int i = 0; i < imgs.length(); i++) {
                        JSONObject im = imgs.getJSONObject(i);
                        content.put(new JSONObject().put("type", "image_url").put("image_url",
                                new JSONObject().put("url", "data:" + im.optString("mime", "image/png")
                                        + ";base64," + im.optString("data", ""))));
                    }
                    msgs.put(new JSONObject().put("role", "user").put("content", content));
                } else {
                    msgs.put(new JSONObject().put("role", "user").put("content", text));
                }
            } else if ("assistant".equals(role)) {
                JSONArray calls = step.optJSONArray("calls");
                if (calls != null && calls.length() > 0) {
                    JSONArray toolCalls = new JSONArray();
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        JSONObject args = c.optJSONObject("args");
                        toolCalls.put(new JSONObject()
                                .put("id", c.optString("id", "c" + callCounter++))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", c.optString("name", ""))
                                        .put("arguments", (args == null ? new JSONObject() : args).toString())));
                    }
                    JSONObject m = new JSONObject().put("role", "assistant");
                    m.put("content", null);
                    m.put("tool_calls", toolCalls);
                    msgs.put(m);
                } else {
                    msgs.put(new JSONObject().put("role", "assistant")
                            .put("content", step.optString("text", "")));
                }
            } else if ("tool".equals(role)) {
                JSONArray results = step.optJSONArray("results");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject res = results.getJSONObject(i);
                        msgs.put(new JSONObject().put("role", "tool")
                                .put("tool_call_id", res.optString("id", "c" + callCounter++))
                                .put("content", res.optString("result", "")));
                    }
                }
            }
        }

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", cfg.temperature / 100.0);
        body.put("max_tokens", cfg.maxTokens);
        body.put("messages", msgs);
        body.put("tools", openAITools());
        body.put("tool_choice", "auto");

        String base = cfg.openaiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";
        String resp = postRetry(url, body.toString(), new String[][]{
                {"Authorization", "Bearer " + cfg.apiKey().trim()}
        });
        JSONObject jo = new JSONObject(resp);
        JSONObject message = jo.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls != null && calls.length() > 0) {
            JSONArray out = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                JSONObject fnObj = c.optJSONObject("function");
                String name = fnObj == null ? "" : fnObj.optString("name", "");
                String argStr = fnObj == null ? "" : fnObj.optString("arguments", "{}");
                JSONObject args;
                try { args = new JSONObject(argStr); } catch (Exception e) { args = new JSONObject(); }
                out.put(new JSONObject().put("id", c.optString("id", ""))
                        .put("name", name).put("args", args));
            }
            return new JSONObject().put("calls", out);
        }
        return new JSONObject().put("answer", message.optString("content", ""));
    }

    private JSONArray googleTools() throws Exception {
        JSONArray decls = new JSONArray();
        if (toolOn("tool.file")) {
            decls.put(decl("file_read", "读取文本文件", "path", new String[]{"path"}));
            decls.put(decl2("file_write", "创建或覆盖文件", "path", "content"));
            decls.put(decl("file_list", "列目录", "path", null));
            decls.put(decl("file_info", "文件信息", "path", null));
            decls.put(decl("file_exists", "检查文件/目录是否存在", "path", new String[]{"path"}));
            decls.put(decl2("file_move", "移动或重命名文件/目录", "path", "to"));
            decls.put(decl2("file_copy", "复制文件/目录", "path", "to"));
            decls.put(decl2("file_zip", "把文件/目录压缩成 zip", "path", "to"));
            decls.put(decl2("file_unzip", "把 zip 解压到目录", "path", "to"));
            if (toolOn("tool.search")) {
                decls.put(decl2("file_find", "按名查找文件", "name", "path"));
                decls.put(decl2("file_grep", "在文件里搜关键词", "path", "keyword"));
            }
        }
        if (toolOn("tool.shell")) {
            decls.put(decl("shell_exec", "执行白名单命令", "command", new String[]{"command"}));
        }
        if (toolOn("tool.http")) {
            decls.put(decl("http_get", "抓取网页", "url", new String[]{"url"}));
        }
        if (toolOn("tool.text")) {
            decls.put(decl("text_base64_encode", "文本转 base64", "text", new String[]{"text"}));
            decls.put(decl("text_base64_decode", "base64 转文本", "text", new String[]{"text"}));
            decls.put(decl("text_url_encode", "URL 编码", "text", new String[]{"text"}));
            decls.put(decl("text_url_decode", "URL 解码", "text", new String[]{"text"}));
            decls.put(declJsonGet("text_json_get", "从 JSON 按点路径取字段"));
            decls.put(decl("text_upper", "转大写", "text", new String[]{"text"}));
            decls.put(decl("text_lower", "转小写", "text", new String[]{"text"}));
            decls.put(decl("text_stats", "统计字符/行/词", "text", new String[]{"text"}));
        }
        if (toolOn("tool.system")) {
            decls.put(decl0("system_device_info", "设备概要(RAM/存储/网络/时间)"));
            decls.put(decl0("system_battery", "电池电量与充电状态"));
            decls.put(decl0("system_storage", "存储剩余/总量"));
            decls.put(decl0("system_network", "网络类型与连接状态"));
            decls.put(decl0("system_clipboard_get", "读剪贴板文本"));
            decls.put(decl("system_clipboard_set", "写剪贴板文本", "text", new String[]{"text"}));
        }
        decls.put(decl0("time_now", "获取当前日期时间"));
        return new JSONArray().put(new JSONObject().put("function_declarations", decls));
    }

    // JSON 取字段：json 必填、path 可选（取整个对象时省略）
    private JSONObject declJsonGet(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("json", new JSONObject().put("type", "string").put("description", "json"));
        props.put("path", new JSONObject().put("type", "string").put("description", "path"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("json"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", o);
    }

    private JSONObject decl(String name, String desc, String p, String[] req) throws Exception {
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", schema(p, req != null && req.length > 0));
    }

    private JSONObject decl2(String name, String desc, String p1, String p2) throws Exception {
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", schema2(p1, true, p2, true));
    }

    private JSONObject decl0(String name, String desc) throws Exception {
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()));
    }

    private JSONObject callGoogle(List<JSONObject> transcript) throws Exception {
        StringBuilder sys = new StringBuilder(systemPrompt());
        JSONArray contents = new JSONArray();
        for (JSONObject step : transcript) {
            String role = step.optString("role");
            if ("user".equals(role)) {
                JSONArray parts = new JSONArray();
                if (step.optString("text", "").length() > 0) parts.put(new JSONObject().put("text", step.optString("text")));
                JSONArray imgs = step.optJSONArray("images");
                if (imgs != null) {
                    for (int i = 0; i < imgs.length(); i++) {
                        JSONObject im = imgs.getJSONObject(i);
                        parts.put(new JSONObject().put("inline_data",
                                new JSONObject().put("mime_type", im.optString("mime", "image/png"))
                                        .put("data", im.optString("data", ""))));
                    }
                }
                contents.put(new JSONObject().put("role", "user").put("parts", parts));
            } else if ("assistant".equals(role)) {
                JSONArray calls = step.optJSONArray("calls");
                JSONArray parts = new JSONArray();
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        JSONObject args = c.optJSONObject("args");
                        parts.put(new JSONObject().put("function_call",
                                new JSONObject().put("name", c.optString("name", ""))
                                        .put("args", args == null ? new JSONObject() : args)));
                    }
                }
                contents.put(new JSONObject().put("role", "model").put("parts", parts));
            } else if ("tool".equals(role)) {
                JSONArray results = step.optJSONArray("results");
                JSONArray parts = new JSONArray();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject res = results.getJSONObject(i);
                        parts.put(new JSONObject().put("function_response",
                                new JSONObject().put("name", res.optString("name", ""))
                                        .put("response", new JSONObject().put("result", res.optString("result", "")))));
                    }
                }
                // Gemini REST 函数结果角色是 "function"
                contents.put(new JSONObject().put("role", "function").put("parts", parts));
            }
        }

        JSONObject body = new JSONObject();
        body.put("contents", contents);
        // 采样参数放 generationConfig，顶层不生效
        JSONObject gen = new JSONObject()
                .put("temperature", cfg.temperature / 100.0)
                .put("maxOutputTokens", cfg.maxTokens);
        body.put("generationConfig", gen);
        body.put("tools", googleTools());
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", sys.toString()))));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + cfg.model + ":generateContent";
        String resp = postRetry(url, body.toString(), new String[][]{
                {"x-goog-api-key", cfg.apiKey().trim()}
        });
        JSONObject jo = new JSONObject(resp);
        JSONArray cand = jo.optJSONArray("candidates");
        JSONArray callsOut = new JSONArray();
        StringBuilder ans = new StringBuilder();
        if (cand != null && cand.length() > 0) {
            JSONArray parts = cand.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                JSONObject fc = p.optJSONObject("functionCall");
                if (fc != null) {
                    callsOut.put(new JSONObject().put("id", "c" + callCounter++)
                            .put("name", fc.optString("name", ""))
                            .put("args", fc.optJSONObject("args") == null ? new JSONObject() : fc.optJSONObject("args")));
                } else {
                    ans.append(p.optString("text", ""));
                }
            }
        }
        JSONObject r = new JSONObject();
        if (callsOut.length() > 0) r.put("calls", callsOut);
        r.put("answer", ans.toString());
        return r;
    }

    private JSONArray anthropicTools() throws Exception {
        JSONArray arr = new JSONArray();
        if (toolOn("tool.file")) {
            arr.put(declA("file_read", "读取文本文件", "path", true));
            arr.put(declA2("file_write", "创建或覆盖文件", "path", "content"));
            arr.put(declA("file_list", "列目录", "path", false));
            arr.put(declA("file_info", "文件信息", "path", false));
            arr.put(declA("file_exists", "检查文件/目录是否存在", "path", true));
            arr.put(declA2("file_move", "移动或重命名文件/目录", "path", "to"));
            arr.put(declA2("file_copy", "复制文件/目录", "path", "to"));
            arr.put(declA2("file_zip", "把文件/目录压缩成 zip", "path", "to"));
            arr.put(declA2("file_unzip", "把 zip 解压到目录", "path", "to"));
            if (toolOn("tool.search")) {
                arr.put(declA2("file_find", "按名查找文件", "name", "path"));
                arr.put(declA2("file_grep", "在文件里搜关键词", "path", "keyword"));
            }
        }
        if (toolOn("tool.shell")) {
            arr.put(declA("shell_exec", "执行白名单命令", "command", true));
        }
        if (toolOn("tool.http")) {
            arr.put(declA("http_get", "抓取网页", "url", true));
        }
        if (toolOn("tool.text")) {
            arr.put(declA("text_base64_encode", "文本转 base64", "text", true));
            arr.put(declA("text_base64_decode", "base64 转文本", "text", true));
            arr.put(declA("text_url_encode", "URL 编码", "text", true));
            arr.put(declA("text_url_decode", "URL 解码", "text", true));
            arr.put(declAJsonGet("text_json_get", "从 JSON 按点路径取字段"));
            arr.put(declA("text_upper", "转大写", "text", true));
            arr.put(declA("text_lower", "转小写", "text", true));
            arr.put(declA("text_stats", "统计字符/行/词", "text", true));
        }
        if (toolOn("tool.system")) {
            arr.put(declA0("system_device_info", "设备概要(RAM/存储/网络/时间)"));
            arr.put(declA0("system_battery", "电池电量与充电状态"));
            arr.put(declA0("system_storage", "存储剩余/总量"));
            arr.put(declA0("system_network", "网络类型与连接状态"));
            arr.put(declA0("system_clipboard_get", "读剪贴板文本"));
            arr.put(declA("system_clipboard_set", "写剪贴板文本", "text", true));
        }
        arr.put(declA0("time_now", "获取当前日期时间"));
        return arr;
    }

    // JSON 取字段：json 必填、path 可选（取整个对象时省略）
    private JSONObject declAJsonGet(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("json", new JSONObject().put("type", "string").put("description", "json"));
        props.put("path", new JSONObject().put("type", "string").put("description", "path"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("json"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", o);
    }

    private JSONObject declA0(String name, String desc) throws Exception {
        JSONObject o = new JSONObject().put("type", "object")
                .put("properties", new JSONObject());
        return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", o);
    }

    private JSONObject declA(String name, String desc, String p, boolean req) throws Exception {
        JSONObject p1 = new JSONObject().put("type", "string").put("description", p);
        JSONObject o = new JSONObject().put("type", "object").put("properties", new JSONObject().put(p, p1));
        if (req) o.put("required", new JSONArray().put(p));
        return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", o);
    }

    private JSONObject declA2(String name, String desc, String p1, String p2) throws Exception {
        JSONObject props = new JSONObject()
                .put(p1, new JSONObject().put("type", "string").put("description", p1))
                .put(p2, new JSONObject().put("type", "string").put("description", p2));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put(p1).put(p2));
        return new JSONObject().put("name", name).put("description", desc).put("input_schema", o);
    }

    private JSONObject callAnthropic(List<JSONObject> transcript) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject step : transcript) {
            String role = step.optString("role");
            if ("user".equals(role)) {
                JSONArray content = new JSONArray();
                if (step.optString("text", "").length() > 0) content.put(new JSONObject().put("type", "text").put("text", step.optString("text")));
                JSONArray imgs = step.optJSONArray("images");
                if (imgs != null) {
                    for (int i = 0; i < imgs.length(); i++) {
                        JSONObject im = imgs.getJSONObject(i);
                        content.put(new JSONObject().put("type", "image").put("source",
                                new JSONObject().put("type", "base64")
                                        .put("media_type", im.optString("mime", "image/png"))
                                        .put("data", im.optString("data", ""))));
                    }
                }
                arr.put(new JSONObject().put("role", "user").put("content", content));
            } else if ("assistant".equals(role)) {
                JSONArray calls = step.optJSONArray("calls");
                JSONArray content = new JSONArray();
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        JSONObject input = c.optJSONObject("args");
                        content.put(new JSONObject().put("type", "tool_use")
                                .put("id", c.optString("id", "c" + callCounter++))
                                .put("name", c.optString("name", ""))
                                .put("input", input == null ? new JSONObject() : input));
                    }
                }
                arr.put(new JSONObject().put("role", "assistant").put("content", content));
            } else if ("tool".equals(role)) {
                JSONArray results = step.optJSONArray("results");
                JSONArray content = new JSONArray();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject res = results.getJSONObject(i);
                        content.put(new JSONObject().put("type", "tool_result")
                                .put("tool_use_id", res.optString("id", ""))
                                .put("content", res.optString("result", "")));
                    }
                }
                arr.put(new JSONObject().put("role", "user").put("content", content));
            }
        }

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("max_tokens", cfg.maxTokens);
        body.put("temperature", Math.min(1.0, cfg.temperature / 100.0));
        body.put("system", systemPrompt());
        body.put("messages", arr);
        body.put("tools", anthropicTools());

        String url = "https://api.anthropic.com/v1/messages";
        String resp = postRetry(url, body.toString(), new String[][]{
                {"x-api-key", cfg.apiKey().trim()},
                {"anthropic-version", "2023-06-01"}
        });
        JSONObject jo = new JSONObject(resp);
        JSONArray blocks = jo.optJSONArray("content");
        JSONArray callsOut = new JSONArray();
        StringBuilder ans = new StringBuilder();
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.getJSONObject(i);
                if ("tool_use".equals(b.optString("type"))) {
                    callsOut.put(new JSONObject().put("id", b.optString("id", "c" + callCounter++))
                            .put("name", b.optString("name", ""))
                            .put("args", b.optJSONObject("input") == null ? new JSONObject() : b.optJSONObject("input")));
                } else if ("text".equals(b.optString("type"))) {
                    ans.append(b.optString("text", ""));
                }
            }
        }
        JSONObject r = new JSONObject();
        if (callsOut.length() > 0) r.put("calls", callsOut);
        r.put("answer", ans.toString());
        return r;
    }

    private String systemPrompt() {
        return "你是一名 Android 上的 AI 助手，可调用文件工具（读/写/列目录/信息/存在性/移动/复制/压缩/解压/查找/grep）、"
                + "文本与数据处理（base64、URL 编解码、JSON 取字段、大小写、字数）、设备状态（电池/存储/网络/剪贴板/设备信息）、"
                + "Shell 白名单命令、网页抓取、时间工具完成用户任务。"
                + "需要时用工具，取到结果后继续；可以全部完成后，用与用户相同的语言简洁回答。";
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
            if (code < 200 || code >= 300) {
                throw new HttpError(code, "HTTP " + code + ": " + resp.substring(0, Math.min(400, resp.length())));
            }
            return resp;
        } finally {
            control.clearConn();
        }
    }

    // 带 HTTP 状态码的异常；postRetry 据此区分「可重试临时错」与「确定性错」
    private static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
        // 仅 5xx / 429 / 408 可重试；其余 4xx 确定性错误直接停
        boolean retryable() {
            return code == 408 || code == 429 || (code >= 500 && code < 600);
        }
    }

    // LLM 请求带退避重试：前 3 次失败各等 1s，之后每次等 2s，循环到成功；用户取消才停。
    // 确定性 4xx 直接抛出不重试；每次重试新建连接（HttpURLConnection 不可复用）。
    private String postRetry(String url, String body, String[][] headers) throws Exception {
        int fails = 0;
        while (true) {
            if (control.shouldStop()) throw new Exception("任务已停止");
            HttpURLConnection conn = null;
            try {
                conn = connPost(url, body);
                for (int i = 0; i < headers.length; i++) {
                    conn.setRequestProperty(headers[i][0], headers[i][1]);
                }
                return post(conn, url, body);
            } catch (Exception e) {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                control.clearConn();
                // 用户取消：不重试了，直接停
                if (control.shouldStop()) throw new Exception("任务已停止", e);
                // 确定性错误（Key 错/参数错/资源不存在等 4xx）重试也不会好，直接抛出去让任务失败
                if (e instanceof HttpError && !((HttpError) e).retryable()) {
                    throw e;
                }
                fails++;
                long wait = (fails <= 3) ? 1000L : 2000L;
                trace.add(new Message("system", "连接失败，" + (wait / 1000)
                        + " 秒后自动重试（第 " + fails + " 次）", System.currentTimeMillis()));
                sleepCancellable(wait);
            }
        }
    }

    // 分段休眠，随时响应取消
    private void sleepCancellable(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!control.shouldStop()) {
            long remain = end - System.currentTimeMillis();
            if (remain <= 0) return;
            try {
                Thread.sleep(remain < 100 ? remain : 100);
            } catch (InterruptedException e) {
                return;
            }
        }
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
            fis.close();
            return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // 工具名归一：别名/同义键映射到标准名
    private String normalizeToolName(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        String[][] aliasMap = {
                {"file_read", "read", "read_file", "cat", "open", "view"},
                {"file_write", "write", "write_file", "save", "create_file"},
                {"file_list", "list", "ls", "list_dir", "list_files", "dir"},
                {"file_find", "find", "find_file"},
                {"file_grep", "grep", "search", "search_file", "search_files"},
                {"file_info", "info", "stat"},
                {"file_exists", "exists", "file_exists_check", "check_file"},
                {"file_move", "move", "move_file", "rename", "rename_file"},
                {"file_copy", "copy", "copy_file"},
                {"file_zip", "zip", "compress", "compress_file", "zip_file"},
                {"file_unzip", "unzip", "extract", "extract_file", "unzip_file"},
                {"time_now", "time", "now", "date", "datetime", "current_time"},
                {"shell_exec", "shell", "exec", "execute", "run", "run_shell"},
                {"http_get", "http", "fetch", "download", "get_url", "web"},
                {"text_base64_encode", "base64_encode", "b64encode", "to_base64"},
                {"text_base64_decode", "base64_decode", "b64decode", "from_base64"},
                {"text_url_encode", "url_encode", "urlencode", "encode_uri"},
                {"text_url_decode", "url_decode", "urldecode", "decode_uri"},
                {"text_json_get", "json_get", "json_path", "pick_json"},
                {"text_upper", "uppercase", "upper", "to_upper"},
                {"text_lower", "lowercase", "lower", "to_lower"},
                {"text_stats", "text_length", "count_words", "word_count"},
                {"system_device_info", "device_info", "device", "hardware", "get_device"},
                {"system_battery", "battery", "battery_level", "batterylevel"},
                {"system_storage", "storage", "storage_info", "disk"},
                {"system_network", "network", "network_info", "connection"},
                {"system_clipboard_get", "clipboard", "clipboard_get", "read_clipboard"},
                {"system_clipboard_set", "clipboard_set", "set_clipboard", "copy_to_clipboard"},
        };
        for (String[] group : aliasMap) {
            for (String alias : group) {
                if (t.equals(alias)) return group[0];
                if (alias.length() >= 4 && t.contains(alias)) return group[0];
            }
        }
        return raw;
    }

    // 工具名 → 工具页开关分组
    private String toolGroup(String name) {
        switch (name) {
            case "file_read":
            case "file_write":
            case "file_list":
            case "file_info":
            case "file_exists":
            case "file_move":
            case "file_copy":
            case "file_zip":
            case "file_unzip":
                return "tool.file";
            case "file_find":
            case "file_grep":
                return "tool.search";
            case "shell_exec":
                return "tool.shell";
            case "http_get":
                return "tool.http";
            case "text_base64_encode":
            case "text_base64_decode":
            case "text_url_encode":
            case "text_url_decode":
            case "text_json_get":
            case "text_upper":
            case "text_lower":
            case "text_stats":
                return "tool.text";
            case "system_device_info":
            case "system_battery":
            case "system_storage":
            case "system_network":
            case "system_clipboard_get":
            case "system_clipboard_set":
                return "tool.system";
            case "time_now":
                return "";   // 时间工具无副作用，始终可用，不受工具页开关门控
            default:
                return "";
        }
    }

    // 工具分发；缺参给可行动提示。工具页停用的已知分组只提示、不执行。
    private String dispatchTool(String name, JSONObject args) {
        try {
            String group = toolGroup(name);
            if (group.length() > 0 && !toolOn(group)) {
                return "工具分组「" + group + "」当前已停用（见「工具」页开关），本条未执行。请改用其它可用工具或在工具页重新启用。";
            }
            switch (name) {
                case "file_read": {
                    String p = str(args, "path", "file", "file_path");
                    if (p.isEmpty()) return "缺 'path'。当前可读文件：\n" + fileTools.list(fileTools.getWorkspace());
                    return fileTools.read(p);
                }
                case "file_write": {
                    String p = str(args, "path", "file", "file_path");
                    if (p.isEmpty()) return "缺 'path'。请在 " + fileTools.getWorkspace() + " 下指定文件（如 notes.txt），再重试 {path, content}。";
                    return fileTools.write(p, str(args, "content", "text"));
                }
                case "file_list":
                    return fileTools.list(str(args, "path", "dir", "directory"));
                case "file_find": {
                    String n = str(args, "name", "query", "keyword");
                    if (n.isEmpty()) return "缺 'name'。可用文件：\n" + fileTools.list(fileTools.getWorkspace());
                    return fileTools.find(str(args, "path", "dir", "directory"), n);
                }
                case "file_grep": {
                    String k = str(args, "keyword", "query");
                    String p = str(args, "path", "file", "file_path");
                    if (p.isEmpty() || k.isEmpty())
                        return "需要 'path' 和 'keyword'。可用文件：\n" + fileTools.list(fileTools.getWorkspace());
                    return fileTools.grep(p, k);
                }
                case "file_info":
                    return fileTools.info(str(args, "path", "file", "file_path"));
                case "file_exists":
                    return fileTools.exists(str(args, "path", "file", "file_path"));
                case "file_move": {
                    String s = str(args, "path", "from", "source", "file", "file_path");
                    String d = str(args, "to", "dest", "destination");
                    if (s.isEmpty() || d.isEmpty()) return "需要 'path'(源) 和 'to'(目标)。";
                    return fileTools.move(s, d);
                }
                case "file_copy": {
                    String s = str(args, "path", "from", "source", "file", "file_path");
                    String d = str(args, "to", "dest", "destination");
                    if (s.isEmpty() || d.isEmpty()) return "需要 'path'(源) 和 'to'(目标)。";
                    return fileTools.copy(s, d);
                }
                case "file_zip": {
                    String s = str(args, "path", "from", "source", "file", "file_path", "dir");
                    String d = str(args, "to", "dest", "destination");
                    if (s.isEmpty() || d.isEmpty()) return "需要 'path'(源文件/目录) 和 'to'(输出 zip 路径)。";
                    return fileTools.zip(s, d);
                }
                case "file_unzip": {
                    String s = str(args, "path", "from", "source", "file", "file_path");
                    String d = str(args, "to", "dest", "destination", "dir");
                    if (s.isEmpty() || d.isEmpty()) return "需要 'path'(zip 文件) 和 'to'(解压目标目录)。";
                    return fileTools.unzip(s, d);
                }
                case "time_now":
                    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE",
                            java.util.Locale.CHINA).format(new java.util.Date());
                case "shell_exec": {
                    String c = str(args, "command", "cmd");
                    if (c.isEmpty()) return "缺 'command'。允许的命令：ls cat echo date uname whoami pwd grep find";
                    int timeout = args.has("timeout_ms") ? args.optInt("timeout_ms", 15000) : 15000;
                    return shell.exec(c, timeout);
                }
                case "http_get": {
                    String u = str(args, "url", "path");
                    if (u.isEmpty()) return "缺 'url'。示例 {\"url\":\"https://example.com\"}。";
                    return httpGet(u);
                }
                case "text_base64_encode": {
                    String t = str(args, "text", "input", "data");
                    if (t.isEmpty()) return "缺 'text'。";
                    return TextTools.base64Encode(t);
                }
                case "text_base64_decode": {
                    String t = str(args, "text", "input", "data");
                    if (t.isEmpty()) return "缺 'text'（base64 串）。";
                    return TextTools.base64Decode(t);
                }
                case "text_url_encode":
                    return TextTools.urlEncode(str(args, "text", "input"));
                case "text_url_decode":
                    return TextTools.urlDecode(str(args, "text", "input"));
                case "text_json_get": {
                    String json = str(args, "json", "text", "input");
                    if (json.isEmpty()) return "缺 'json'。";
                    return TextTools.jsonGet(json, str(args, "path", "pointer"));
                }
                case "text_upper":
                    return TextTools.toUpperCase(str(args, "text", "input"));
                case "text_lower":
                    return TextTools.toLowerCase(str(args, "text", "input"));
                case "text_stats":
                    return TextTools.textStats(str(args, "text", "input"));
                case "system_device_info":
                    return systemTools.deviceInfo();
                case "system_battery":
                    return systemTools.batteryStatus();
                case "system_storage":
                    return systemTools.storageInfo();
                case "system_network":
                    return systemTools.networkInfo();
                case "system_clipboard_get":
                    return systemTools.clipboardGet();
                case "system_clipboard_set": {
                    String t = str(args, "text", "input");
                    return systemTools.clipboardSet(t);
                }
                default:
                    return "未知工具 '" + name + "'。可用：file_read, file_write, file_list, file_info, file_exists, file_move, file_copy, file_zip, file_unzip, file_find, file_grep, shell_exec, http_get, "
                            + "text_base64_encode, text_base64_decode, text_url_encode, text_url_decode, text_json_get, text_upper, text_lower, text_stats, "
                            + "system_device_info, system_battery, system_storage, system_network, system_clipboard_get, system_clipboard_set, time_now。";
            }
        } catch (Exception e) {
            return "工具出错: " + e.getMessage();
        }
    }

    // 参数键名宽容：主键缺失时按同义键顺序取值
    private String str(JSONObject o, String key, String... alts) {
        String v = o.optString(key, "").trim();
        if (v.length() > 0) return v;
        for (String a : alts) {
            v = o.optString(a, "").trim();
            if (v.length() > 0) return v;
        }
        return "";
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
            return "http 出错: " + e.getMessage();
        }
    }

    private List<Message> recentWindow(List<Message> all, int n) {
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    private String localFallback(String task, List<Message> history, List<Attachment> images) {
        StringBuilder sb = new StringBuilder();
        sb.append("收到「").append(task).append("」。\n");
        if (images != null && !images.isEmpty()) {
            sb.append("已收到 ").append(images.size()).append(" 个附件：");
            for (Attachment a : images) sb.append(a.fileName).append(' ');
            sb.append("（已存入工作区 ").append(fileTools.getWorkspace()).append("）\n");
        }
        sb.append("当前是离线本地模式（未配置 API Key），未能真正理解任务。");
        sb.append("在配置页填 ").append(providerLabel()).append(" 的 API Key 后我可真正理解并调用工具完成它。");
        return sb.toString();
    }

    private String providerLabel() {
        switch (cfg.getProvider()) {
            case Config.PROVIDER_GOOGLE:   return "Google";
            case Config.PROVIDER_ANTHROPIC: return "Anthropic";
            default:                        return "OpenAI";
        }
    }
}
