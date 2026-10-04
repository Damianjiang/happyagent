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

    private static final int DEFAULT_MAX_STEPS = 10;
    private static final int HISTORY_WINDOW = 12;
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;
    // 喂给模型的工具结果上限（保留头尾），防长输出把上下文塞爆导致模型乱走
    private static final int MAX_TOOL_RESULT = 8000;
    // 单张图 base64 前原始字节上限（约 4MB 原始 → base64 后 ~5.3MB）。老机多图同发不至于把堆撑爆。
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

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
    // 角色卡 / 世界书：一段设定文本，注入系统提示词（AgentBackend 从 Prefs 读入）
    private String roleCard = "";
    // 自定义系统提示词：用户在设置页编辑的追加指令，真注入引擎（留空=只用内置默认）
    private String customSystemPrompt = "";
    // 标签 / 提示词片段：启用段拼接后的正文，真注入引擎（留空=无）
    private String promptTags = "";
    // 任务最大步数：默认 10，AgentBackend 按 Prefs 覆写（老存档兼容，不进序列化）
    private int maxSteps = DEFAULT_MAX_STEPS;

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

    // 角色卡 / 世界书：设了就在系统提示词开头套上人设
    public void setRoleCard(String roleCard) {
        this.roleCard = roleCard == null ? "" : roleCard.trim();
    }

    // 自定义系统提示词：设置页编辑的追加指令，注入到角色设定之后、工具规则之前
    public void setCustomSystemPrompt(String s) {
        this.customSystemPrompt = s == null ? "" : s.trim();
    }

    // 标签 / 提示词片段：启用段拼接后的正文，注入到自定义指令之后、工具规则之前
    public void setPromptTags(String s) {
        this.promptTags = s == null ? "" : s.trim();
    }

    // 任务最大步数（AgentBackend 按 Prefs 设，允许 4~20；越界回默认）
    public void setMaxSteps(int n) {
        this.maxSteps = (n < 4 || n > 20) ? DEFAULT_MAX_STEPS : n;
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

        for (int step = 1; step <= maxSteps; step++) {
            if (control.shouldStop()) return cancelled();
            control.waitForResume();
            if (control.shouldStop()) return cancelled();

            trace.add(new Message("system", "STEP " + step + " / " + maxSteps, System.currentTimeMillis()));
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
                    int total = calls.length();
                    for (int i = 0; i < total; i++) {
                        JSONObject c = calls.getJSONObject(i);
                        String toolName = normalizeToolName(c.optString("name", ""));
                        // 参数容错：先校验必填，缺参/未知工具走软性提示（不执行、不给硬错误）
                        SoftCheck pre = precheckTool(toolName, c.optJSONObject("args"));
                        if (pre != null) {
                            results.put(new JSONObject()
                                    .put("id", c.optString("id", ""))
                                    .put("name", toolName)
                                    .put("result", pre.msg));
                            trace.add(new Message("tool", toolName + " ⚠ " + pre.msg, System.currentTimeMillis()));
                            continue;
                        }
                        JSONObject args = c.optJSONObject("args");
                        if (args == null) args = new JSONObject();
                        String result = dispatchTool(toolName, args);
                        // 软性容错：工具执行报参数/类型问题 → 包一层"请重写"提示，不硬报错
                        if (isSoftError(result)) {
                            result = "参数或调用有问题，未执行成功：\n" + result
                                    + "\n（请按上面提示修正参数、重写这次调用再试；不要放弃，也不要重复同样的错误调用。）";
                        }
                        // 长结果截断后再喂模型（trace 保留全文，界面显示不受影响）
                        results.put(new JSONObject()
                                .put("id", c.optString("id", ""))
                                .put("name", toolName)
                                .put("result", truncateForLlm(result)));
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
        return "Reached max steps (" + maxSteps + ").";
    }

    private String cancelled() {
        trace.add(new Message("system", "Agent stopped by user", System.currentTimeMillis()));
        return "任务已停止";
    }

    // 工具结果进模型前截断：超长保留头 70% + 尾 30%，中间标省略；界面 trace 仍是全文
    private static String truncateForLlm(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_TOOL_RESULT) return s;
        int head = (int) (MAX_TOOL_RESULT * 0.7);
        int tail = MAX_TOOL_RESULT - head;
        return s.substring(0, head)
                + "\n…(中间 " + (s.length() - head - tail) + " 字符已省略，文件/网页全文用 file_read 分页读)…\n"
                + s.substring(s.length() - tail);
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

    // 单轮纯文本生成（无工具、无本地降级）：给"AI 生成角色卡/文案"等一次性生成用。
    // 诚实：没 Key 直接说明、失败照实返回错误串，绝不编造内容。三家直连。
    public String oneShot(String userText) throws Exception {
        if (!cfg.hasKey()) {
            return "没有配置 API Key，无法在线生成（本机未内置模型）。";
        }
        String agent = cfg.agentName == null || cfg.agentName.trim().isEmpty()
                ? "Happy Agent" : cfg.agentName.trim();
        String provider = cfg.getProvider();
        if (Config.PROVIDER_GOOGLE.equals(provider)) {
            JSONObject body = new JSONObject();
            body.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", userText)))));
            body.put("generationConfig", new JSONObject()
                    .put("temperature", clampTemp(cfg.temperature / 100.0, 2.0))
                    .put("maxOutputTokens", 4096));
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + cfg.model + ":generateContent";
            String resp = postRetry(url, body.toString(), new String[][]{
                    {"x-goog-api-key", cfg.apiKey().trim()}});
            JSONObject jo = new JSONObject(resp);
            JSONArray cand = jo.optJSONArray("candidates");
            if (cand == null || cand.length() == 0) return "上游无返回（" + cfg.model + "）";
            JSONArray parts = cand.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
            return sb.toString();
        }
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            body.put("max_tokens", 4096);
            body.put("system", "你是「" + agent + "」的内容生成助手，按用户要求简洁输出。");
            body.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", userText)));
            String resp = postRetry("https://api.anthropic.com/v1/messages", body.toString(), new String[][]{
                    {"x-api-key", cfg.apiKey().trim()}, {"anthropic-version", "2023-06-01"}});
            JSONObject jo = new JSONObject(resp);
            JSONArray content = jo.optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) for (int i = 0; i < content.length(); i++) sb.append(content.getJSONObject(i).optString("text", ""));
            return sb.toString();
        }
        // OpenAI 兼容
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", clampTemp(cfg.temperature / 100.0, 2.0));
        body.put("max_tokens", 4096);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system")
                        .put("content", "你是「" + agent + "」的内容生成助手，按用户要求简洁输出。"))
                .put(new JSONObject().put("role", "user").put("content", userText)));
        String base = cfg.openaiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String resp = postRetry(base + "/chat/completions", body.toString(), new String[][]{
                {"Authorization", "Bearer " + cfg.apiKey().trim()}});
        JSONObject jo = new JSONObject(resp);
        return jo.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
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
            arr.put(fn("file_edit", "把文件里 old_text 那段替换成 new_text（局部改，不用重发整文件；写代码/改文本用它）",
                    schemaEdit()));
            arr.put(fn("file_append", "在文件末尾追加内容（文件不存在则新建）", schema2("path", true, "content", true)));
            arr.put(fn("file_delete", "删除文件/目录（工作区内，不可恢复，操作前确认）", schema("path", true)));
            arr.put(fn("file_mkdir", "新建目录（可多级）", schema("path", true)));
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
            arr.put(fn("http_post", "向 URL 发 POST 请求（可带请求体）", schemaPost()));
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
            arr.put(fn("text_calc", "四则运算（+ - * / % 括号）", schema("text", true)));
        }
        if (toolOn("tool.system")) {
            arr.put(fn("system_device_info", "设备概要(RAM/存储/网络/时间)", schema0()));
            arr.put(fn("system_battery", "电池电量与充电状态", schema0()));
            arr.put(fn("system_storage", "存储剩余/总量", schema0()));
            arr.put(fn("system_network", "网络类型与连接状态", schema0()));
            arr.put(fn("system_clipboard_get", "读剪贴板文本", schema0()));
            arr.put(fn("system_clipboard_set", "写剪贴板文本", schema("text", true)));
        }
        if (toolOn("tool.gui")) {
            arr.put(fn("gui_dump", "读取当前屏幕：列出可点击/可输入的元素及文本（需开启无障碍服务）", schema0()));
            arr.put(fn("gui_click", "按文本点当前屏幕上的按钮/条目", schema("query", true)));
            arr.put(fn("gui_type", "向当前屏幕的可输入框输入文本", schema("text", true)));
        }
        if (toolOn("tool.proot")) {
            arr.put(fn("shell_proot", "在免 root Linux 容器(proot+Alpine)里跑一条命令 {command, timeout_ms?}", schema2("command", true, "timeout_ms", false)));
            arr.put(fn("proot_status", "查容器环境状态：是否已部署/架构/能否执行（无参）", schema0()));
            arr.put(fn("proot_setup", "容器环境指引：未部署时返回一键部署入口（部署本身在设置页异步进行，无参）", schema0()));
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

    // http_post：url 必填，body 可选
    private JSONObject schemaPost() throws Exception {
        JSONObject props = new JSONObject();
        props.put("url", new JSONObject().put("type", "string").put("description", "url"));
        props.put("body", new JSONObject().put("type", "string")
                .put("description", "可选请求体（按 application/json 发送）"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("url"));
        return o;
    }

    // file_edit：path/old_text/new_text 必填，replace_count 可选（数字；不传=全部）
    private JSONObject schemaEdit() throws Exception {
        JSONObject props = new JSONObject();
        props.put("path", new JSONObject().put("type", "string")
                .put("description", "文件路径（相对工作区或绝对）"));
        props.put("old_text", new JSONObject().put("type", "string")
                .put("description", "要替换的原文片段（须精确匹配，含缩进换行）"));
        props.put("new_text", new JSONObject().put("type", "string")
                .put("description", "替换成的新内容（可为空串表示删除该段）"));
        props.put("replace_count", new JSONObject().put("type", "number")
                .put("description", "可选：只替换前 N 处；不传=替换全部"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("path").put("old_text").put("new_text"));
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
        body.put("temperature", clampTemp(cfg.temperature / 100.0, 2.0));
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
                // 弱智 AI 兼容：arguments 可能是带引号/单引号/带尾巴的"飞 JSON"，用宽容解析救回；救不回给空对象（下游软校验会提示重写）
                JSONObject args = lenientArgs(argStr);
                if (args == null) args = new JSONObject();
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
            decls.put(declEdit("file_edit", "把文件里 old_text 那段替换成 new_text（局部改，不用重发整文件；写代码/改文本用它）"));
            decls.put(decl2("file_append", "在文件末尾追加内容（文件不存在则新建）", "path", "content"));
            decls.put(decl("file_delete", "删除文件/目录（工作区内，不可恢复）", "path", new String[]{"path"}));
            decls.put(decl("file_mkdir", "新建目录（可多级）", "path", new String[]{"path"}));
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
            decls.put(declPost("http_post", "向 URL 发 POST 请求（可带请求体）"));
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
            decls.put(decl("text_calc", "四则运算（+ - * / % 括号）", "text", new String[]{"text"}));
        }
        if (toolOn("tool.system")) {
            decls.put(decl0("system_device_info", "设备概要(RAM/存储/网络/时间)"));
            decls.put(decl0("system_battery", "电池电量与充电状态"));
            decls.put(decl0("system_storage", "存储剩余/总量"));
            decls.put(decl0("system_network", "网络类型与连接状态"));
            decls.put(decl0("system_clipboard_get", "读剪贴板文本"));
            decls.put(decl("system_clipboard_set", "写剪贴板文本", "text", new String[]{"text"}));
        }
        if (toolOn("tool.gui")) {
            decls.put(decl0("gui_dump", "读取当前屏幕元素（需无障碍服务）"));
            decls.put(decl("gui_click", "按文本点屏幕按钮", "query", new String[]{"query"}));
            decls.put(decl("gui_type", "向可输入框输入文本", "text", new String[]{"text"}));
        }
        if (toolOn("tool.proot")) {
            decls.put(decl("shell_proot", "在免 root Linux 容器(proot+Alpine)里跑命令", "command", new String[]{"command"}));
            decls.put(decl0("proot_status", "查容器环境状态：是否部署/架构/能否执行"));
            decls.put(decl0("proot_setup", "容器未部署时返回一键部署指引"));
        }
        decls.put(decl0("time_now", "获取当前日期时间"));
        return new JSONArray().put(new JSONObject().put("function_declarations", decls));
    }

    // file_edit：path/old_text/new_text 必填，replace_count 可选（数字；不传=全部）
    private JSONObject declEdit(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("path", new JSONObject().put("type", "string").put("description", "文件路径"));
        props.put("old_text", new JSONObject().put("type", "string").put("description", "要替换的原文（精确匹配）"));
        props.put("new_text", new JSONObject().put("type", "string").put("description", "替换成的新内容"));
        props.put("replace_count", new JSONObject().put("type", "number").put("description", "可选，只替换前 N 处"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("path").put("old_text").put("new_text"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", o);
    }

    // http_post：url 必填，body 可选
    private JSONObject declPost(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("url", new JSONObject().put("type", "string").put("description", "url"));
        props.put("body", new JSONObject().put("type", "string")
                .put("description", "可选请求体（按 application/json 发送）"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("url"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", o);
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
                .put("temperature", clampTemp(cfg.temperature / 100.0, 2.0))
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
            arr.put(declAEdit("file_edit", "把文件里 old_text 那段替换成 new_text（局部改，不用重发整文件；写代码/改文本用它）"));
            arr.put(declA2("file_append", "在文件末尾追加内容（文件不存在则新建）", "path", "content"));
            arr.put(declA("file_delete", "删除文件/目录（工作区内，不可恢复）", "path", true));
            arr.put(declA("file_mkdir", "新建目录（可多级）", "path", true));
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
            arr.put(declAPost("http_post", "向 URL 发 POST 请求（可带请求体）"));
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
            arr.put(declA("text_calc", "四则运算（+ - * / % 括号）", "text", true));
        }
        if (toolOn("tool.system")) {
            arr.put(declA0("system_device_info", "设备概要(RAM/存储/网络/时间)"));
            arr.put(declA0("system_battery", "电池电量与充电状态"));
            arr.put(declA0("system_storage", "存储剩余/总量"));
            arr.put(declA0("system_network", "网络类型与连接状态"));
            arr.put(declA0("system_clipboard_get", "读剪贴板文本"));
            arr.put(declA("system_clipboard_set", "写剪贴板文本", "text", true));
        }
        if (toolOn("tool.gui")) {
            arr.put(declA0("gui_dump", "读取当前屏幕元素（需无障碍服务）"));
            arr.put(declA("gui_click", "按文本点屏幕按钮", "query", true));
            arr.put(declA("gui_type", "向可输入框输入文本", "text", true));
        }
        if (toolOn("tool.proot")) {
            arr.put(declA("shell_proot", "在免 root Linux 容器(proot+Alpine)里跑命令", "command", true));
            arr.put(declA0("proot_status", "查容器环境状态：是否部署/架构/能否执行"));
            arr.put(declA0("proot_setup", "容器未部署时返回一键部署指引"));
        }
        arr.put(declA0("time_now", "获取当前日期时间"));
        return arr;
    }

    // file_edit：path/old_text/new_text 必填，replace_count 可选（数字）
    private JSONObject declAEdit(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("path", new JSONObject().put("type", "string").put("description", "文件路径"));
        props.put("old_text", new JSONObject().put("type", "string").put("description", "要替换的原文（精确匹配）"));
        props.put("new_text", new JSONObject().put("type", "string").put("description", "替换成的新内容"));
        props.put("replace_count", new JSONObject().put("type", "number").put("description", "可选，只替换前 N 处"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("path").put("old_text").put("new_text"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", o);
    }

    // http_post：url 必填，body 可选
    private JSONObject declAPost(String name, String desc) throws Exception {
        JSONObject props = new JSONObject();
        props.put("url", new JSONObject().put("type", "string").put("description", "url"));
        props.put("body", new JSONObject().put("type", "string")
                .put("description", "可选请求体（按 application/json 发送）"));
        JSONObject o = new JSONObject().put("type", "object").put("properties", props)
                .put("required", new JSONArray().put("url"));
        return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", o);
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
        // 系统提示：把工具怎么用、参数怎么传、出错怎么办讲清楚，减少反复调用失败。
        String agent = (cfg.agentName == null || cfg.agentName.trim().isEmpty())
                ? "Happy Agent" : cfg.agentName.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(agent).append("」，运行在安卓上的任务助手，")
          .append("通过调用工具完成用户任务，再用与用户相同的语言简洁汇报。\n");
        if (roleCard.length() > 0) {
            sb.append("\n【角色设定 / 世界书（务必遵守）】\n").append(roleCard).append("\n");
        }
        if (customSystemPrompt.length() > 0) {
            sb.append("\n【用户自定义指令（务必遵守）】\n").append(customSystemPrompt).append("\n");
        }
        if (promptTags.length() > 0) {
            sb.append("\n【启用标签 / 提示词片段（务必遵守）】\n").append(promptTags).append("\n");
        }
        sb.append("\n【工具使用规则】\n");
        sb.append("1. 一次只调一个工具，拿到结果再决定下一步；不要一次塞多个。\n");
        sb.append("2. 参数必须精确。改文件前先 file_read 看清内容，再操作；不要凭空猜路径或内容。\n");
        sb.append("3. 路径用相对名即可（如 notes.txt、src/App.java），会自动落到工作区；也可用绝对路径。\n");
        sb.append("4. 改文件优先用 file_edit（只给 old_text 那段原文和 new_text 替换，局部改），不要整文件重发 file_write；整文件重写才用 file_write；末尾加内容用 file_append。\n");
        sb.append("   - file_edit 的 old_text 必须和文件里那段完全一致（含缩进、换行），否则会\"没找到\"。\n");
        sb.append("   - 工具返回\"没找到要替换的内容\"或\"缺某参数\"时，按提示修正参数重试，不要放弃。\n");
        sb.append("5. 写代码 / 改文本 / 处理数据：先读，再用 file_edit 精确改；别把整个文件重写一遍。\n");
        sb.append("6. 工具返回错误时，读懂错误里给出的提示（它常附上可用文件清单/示例参数），改正后重试；同一处最多重试 2 次，仍失败就如实汇报卡在哪。\n");
        sb.append("   - 工具结果里若出现「未执行成功 / 缺参 / 未识别 / 请重写」这类提示，说明这次调用参数有问题：把参数补齐、改成正确格式后重新发起调用即可，不用向用户道歉或放弃。\n");
        sb.append("\n【可用工具】\n");
        sb.append("文件：file_read 读 / file_write 整写 / file_edit 局部替换(old_text→new_text) / file_append 末尾追加 / "
                + "file_delete 删除 / file_mkdir 新建目录 / "
                + "file_list 列目录 / file_info 信息 / file_exists 存在性 / file_move 移动改名 / file_copy 复制 / "
                + "file_zip 压缩 / file_unzip 解压 / file_find 按名找 / file_grep 搜关键词\n"
                + "  - 删除是不可恢复的操作，只有用户明确要求删时才调 file_delete，删前可先 file_info 确认。\n");
        sb.append("文本：text_base64_encode/decode、text_url_encode/decode、text_json_get(按点路径取字段)、text_upper/lower、text_stats、text_calc(四则运算 3+4*2 这种)\n");
        sb.append("设备(只读)：system_device_info / system_battery / system_storage / system_network / system_clipboard_get / system_clipboard_set\n");
        sb.append("GUI(需用户先在系统里开无障碍服务)：gui_dump(读当前屏幕元素) / gui_click(按文本点按钮) / gui_type(向可输入框输入)\n");
        sb.append("  - 想做 GUI 操作前先 gui_dump 看屏幕上有什么，再按文本点/输；没开服务会返回提示，如实告诉用户即可，别硬点。\n");
        sb.append("容器(proot 免 root Linux，需先在设置页一键部署)：shell_proot(容器里跑命令，如 apk add / python / git) / proot_status(查容器状态) / proot_setup(部署指引)\n");
        sb.append("  - 跑 shell_proot 前先 proot_status 看是否就绪；未就绪就如实告诉用户去「设置→容器环境」一键部署，别假装能跑。容器里可装 python3/gcc 等，比白名单 shell_exec 强得多。\n");
        sb.append("其它：shell_exec(白名单命令) / http_get(抓网页) / http_post(发POST请求可带JSON体) / time_now(当前时间)\n");
        sb.append("\n完成所有工具调用后，用两三句话总结做了什么即可，别把工具原始输出整段贴回来。");
        return sb.toString();
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
            if (is == null) throw new HttpError(code, "empty response body");
            // 限 2MB 防恶意/异常响应撑爆内存
            final int MAX_RESP = 2 * 1024 * 1024;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                bo.write(buf, 0, n);
                total += n;
                if (total > MAX_RESP) break;
            }
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

    // LLM 请求带退避重试：前 3 次各等 1s，4-8 次各等 2s，最多 8 次后放弃；用户取消随时停。
    // 确定性 4xx 直接抛出不重试；每次重试新建连接（HttpURLConnection 不可复用）。
    private static final int MAX_RETRIES = 8;
    private String postRetry(String url, String body, String[][] headers) throws Exception {
        int fails = 0;
        while (true) {
            if (control.shouldStop()) throw new Exception("任务已停止");
            if (fails >= MAX_RETRIES) {
                throw new Exception("连接重试 " + MAX_RETRIES + " 次仍失败，请检查网络或 API Key");
            }
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
            // 内存上限：老机（尤其 6 台 2/3GB）多图同发时防 OOM，超上限就不带这张图
            while ((n = fis.read(buf)) != -1) {
                if (bo.size() > MAX_IMAGE_BYTES) {
                    fis.close();
                    return null;
                }
                bo.write(buf, 0, n);
            }
            fis.close();
            return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 弱智 AI 兼容：工具参数软性校验 + 错误提示（不硬报错、让 AI 重写） =====
    // 已知工具必填参数表（参数名；空表 = 无必填）。缺参/未知工具只走"软提示"，不执行、不崩。
    private static final String[][] REQUIRED_PARAMS = {
        {"file_read", "path"},
        {"file_write", "path", "content"},
        {"file_list"},
        {"file_info", "path"},
        {"file_exists", "path"},
        {"file_move", "path", "to"},
        {"file_copy", "path", "to"},
        {"file_zip", "path", "to"},
        {"file_unzip", "path", "to"},
        {"file_edit", "path", "old_text"},
        {"file_append", "path", "content"},
        {"file_delete", "path"},
        {"file_mkdir", "path"},
        {"file_find", "name"},
        {"file_grep", "path", "keyword"},
        {"shell_exec", "command"},
        {"http_get", "url"},
        {"http_post", "url"},
        {"text_base64_encode", "text"},
        {"text_base64_decode", "text"},
        {"text_url_encode", "text"},
        {"text_url_decode", "text"},
        {"text_json_get", "json"},
        {"text_upper", "text"},
        {"text_lower", "text"},
        {"text_stats", "text"},
        {"text_calc", "text"},
        {"system_clipboard_set", "text"},
        {"gui_click", "query"},
        {"gui_type", "text"},
        {"shell_proot", "command"},
    };

    // 取某工具必填参数列表；未知工具返回 null（不强制校验）
    private String[] requiredFor(String tool) {
        for (String[] row : REQUIRED_PARAMS) {
            if (tool.equals(row[0])) {
                String[] out = new String[row.length - 1];
                for (int i = 1; i < row.length; i++) out[i - 1] = row[i];
                return out;
            }
        }
        return null;
    }

    // 是否为已知工具：带分组的 + 一批零参工具（system_* / gui_dump / proot_* / time_now）。
    // 零参工具的分组 key 为空串（time_now 无副作用常开），不能靠 toolGroup 判"未知"。
    private boolean isKnownTool(String tool) {
        if (toolGroup(tool).length() > 0) return true;
        switch (tool) {
            case "time_now":
            case "system_device_info":
            case "system_battery":
            case "system_storage":
            case "system_network":
            case "system_clipboard_get":
            case "gui_dump":
            case "proot_status":
            case "proot_setup":
                return true;
            default:
                return false;
        }
    }

    private static final class SoftCheck {
        final String msg;
        SoftCheck(String m) { this.msg = m; }
    }

    // 执行前软性校验：未知工具 / 缺必填参数 → 返回可行动提示（不执行、不硬报错，让 AI 重写）
    private SoftCheck precheckTool(String tool, JSONObject args) {
        if (tool.length() == 0 || !isKnownTool(tool)) {
            return new SoftCheck("未识别的工具名（\"" + tool + "\"）。请改用列表中的标准工具名"
                    + "（file_read / file_write / file_edit / file_append / file_list / http_get / http_post / "
                    + "text_calc / time_now / shell_proot 等），并带上正确参数重写调用。");
        }
        String[] req = requiredFor(tool);
        if (req == null || req.length == 0) return null;
        JSONObject a = args == null ? new JSONObject() : args;
        StringBuilder missing = new StringBuilder();
        for (String p : req) {
            if (a.optString(p, "").trim().length() == 0) {
                if (missing.length() > 0) missing.append(", ");
                missing.append('\'').append(p).append('\'');
            }
        }
        if (missing.length() > 0) {
            return new SoftCheck("工具 " + tool + " 缺必填参数：" + missing
                    + "。请补齐这些参数、重写这次调用再试；不要重复缺参的调用，也不要凭空猜参数值。");
        }
        return null;
    }

    // 判断工具结果是否为"软错误"（缺参/没找到/路径不存在/命令失败等）→ 提示 AI 重写而非硬报错。
    // 只看结果开头（前 80 字符）的错误前缀，避免"成功读出的文件正文里恰好含 error/failed 字样"被误判成失败。
    // 中文提示（ReactAgent 缺参/分组停用）与英文结果（FileTools "not found" 等）都按开头匹配。
    private boolean isSoftError(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.length() == 0) return false;
        String head = r.length() > 80 ? r.substring(0, 80) : r;
        String low = head.toLowerCase();
        if (head.contains("缺 '") || head.contains("缺\"") || head.contains("缺 必填")
                || head.contains("未识别") || head.contains("请重写")
                || head.contains("路径越界") || head.contains("工具出错")
                || head.contains("http 出错") || head.contains("未知工具")
                || head.contains("无法计算") || head.contains("已停用")
                || head.contains("命令失败") || head.contains("执行失败")
                || head.contains("Permission denied") || head.contains("示例 {")) {
            return true;
        }
        // 英文错误前缀（FileTools/Shell 失败）只在开头出现才算，正文里的同词不触发
        return low.startsWith("not found") || low.startsWith("is a directory")
                || low.startsWith("too large") || low.startsWith("file too large")
                || low.startsWith("read error") || low.startsWith("write error")
                || low.startsWith("edit error") || low.startsWith("append error")
                || low.startsWith("move failed") || low.startsWith("zip error")
                || low.startsWith("unzip error") || low.startsWith("grep error")
                || low.startsWith("list failed") || low.startsWith("cannot delete")
                || low.startsWith("cannot create") || low.startsWith("not a dir")
                || low.startsWith("http 4") || low.startsWith("http 5");
    }

    // 弱智 AI 兼容：把 AI 可能输出的"不规范 JSON 参数"（前后带引号/多余文本/单引号）清洗成可解析对象
    private static JSONObject lenientArgs(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 形如 "{...}" 被包在引号里：{"{...}"} 或 '{"path":"x"}'
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        try { return new JSONObject(s); } catch (Exception ignored) {}
        // 单引号键/值 → 双引号（弱 AI 常见）
        try { return new JSONObject(s.replace('\'', '"')); } catch (Exception ignored) {}
        // 结尾多了引号/文本尾巴：截到最后一个 '}' 再解析
        int lastBrace = s.lastIndexOf('}');
        if (lastBrace > 0) {
            try { return new JSONObject(s.substring(0, lastBrace + 1)); } catch (Exception ignored) {}
        }
        // 取第一个 { 到最后一个 } 之间的子串再试
        int lb = s.indexOf('{');
        int rb = s.lastIndexOf('}');
        if (lb >= 0 && rb > lb) {
            try { return new JSONObject(s.substring(lb, rb + 1)); } catch (Exception ignored) {}
        }
        return null;
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
                {"file_edit", "edit", "edit_file", "replace", "replace_text", "patch", "sed", "find_replace"},
                {"file_append", "append", "append_file", "add_to_file", "add_line", "write_line"},
                {"file_delete", "delete_file", "rm", "remove_file", "delete"},
                {"file_mkdir", "mkdir_file", "make_dir", "create_dir", "mkdir"},
                {"time_now", "time", "now", "date", "datetime", "current_time"},
                {"shell_exec", "shell", "exec", "execute", "run", "run_shell"},
                // http_post 组必须在 http_get 前：否则 "http_post" 会被 http_get 的短别名 "http" 误判
                {"http_post", "post", "post_request", "post_url", "api_call", "request"},
                {"http_get", "http", "fetch", "download", "get_url", "web"},
                {"text_calc", "calc", "calculator", "evaluate", "math", "arithmetic", "expression"},
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
                {"gui_dump", "gui", "gui_screen", "ui_dump", "screen_dump"},
                {"gui_click", "gui_tap", "gui_press"},
                {"gui_type", "gui_input", "gui_type_text"},
                {"shell_proot", "proot", "container", "linux_shell", "chroot_shell", "run_in_container"},
                {"proot_status", "proot_env", "container_status", "proot_check"},
                {"proot_setup", "proot_install", "container_setup", "proot_deploy"},
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
            case "file_edit":
            case "file_append":
            case "file_delete":
            case "file_mkdir":
                return "tool.file";
            case "file_find":
            case "file_grep":
                return "tool.search";
            case "shell_exec":
                return "tool.shell";
            case "http_get":
            case "http_post":
                return "tool.http";
            case "text_base64_encode":
            case "text_base64_decode":
            case "text_url_encode":
            case "text_url_decode":
            case "text_json_get":
            case "text_upper":
            case "text_lower":
            case "text_stats":
            case "text_calc":
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
            case "gui_dump":
            case "gui_click":
            case "gui_type":
                return "tool.gui";
            case "shell_proot":
            case "proot_status":
            case "proot_setup":
                return "tool.proot";
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
                case "file_edit": {
                    String p = str(args, "path", "file", "file_path");
                    String oldT = str(args, "old_text", "old", "oldText", "from", "search");
                    String newT = str(args, "new_text", "new", "newText", "to", "replacement");
                    int rc = safeInt(args, "replace_count", -1);
                    if (p.isEmpty()) return "缺 'path'。当前可改的文件：\n" + fileTools.list(fileTools.getWorkspace())
                            + "\n参数：{path, old_text(要替换的原文), new_text(新内容), replace_count(可选)}";
                    if (oldT.isEmpty()) return "缺 'old_text'（要替换的那段原文，须精确匹配）。只想整文件写入用 file_write，只想末尾追加用 file_append。";
                    return fileTools.edit(p, oldT, newT, rc);
                }
                case "file_append": {
                    String p = str(args, "path", "file", "file_path");
                    String c = str(args, "content", "text", "text_to_append", "line", "data");
                    if (p.isEmpty()) return "缺 'path'。当前文件：\n" + fileTools.list(fileTools.getWorkspace());
                    if (c.isEmpty()) return "缺 'content'（要追加的文本）。";
                    return fileTools.append(p, c);
                }
                case "file_delete": {
                    String p = str(args, "path", "file", "file_path", "name");
                    if (p.isEmpty()) return "缺 'path'（要删除的文件/目录名）。删除不可恢复，请确认。";
                    return fileTools.delete(p);
                }
                case "file_mkdir": {
                    String p = str(args, "path", "dir", "directory");
                    if (p.isEmpty()) return "缺 'path'（要新建的目录名）。";
                    return fileTools.mkdir(p);
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
                case "http_post": {
                    String u = str(args, "url", "path");
                    if (u.isEmpty()) return "缺 'url'。示例 {\"url\":\"https://api.example.com\",\"body\":\"{...}\"}。";
                    return httpPost(u, str(args, "body", "data", "payload"));
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
                case "text_calc": {
                    String e = str(args, "text", "expr", "expression", "input");
                    if (e.isEmpty()) return "缺 'text'（表达式，如 3+4*2）。";
                    return TextTools.calc(e);
                }
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
                case "gui_dump": {
                    com.happyagent.mobile.service.GuardService g =
                            com.happyagent.mobile.service.GuardService.getInstance();
                    if (g == null) return guiNotEnabled();
                    return g.dumpScreen();
                }
                case "gui_click": {
                    com.happyagent.mobile.service.GuardService g =
                            com.happyagent.mobile.service.GuardService.getInstance();
                    if (g == null) return guiNotEnabled();
                    String q = str(args, "query", "text", "label");
                    if (q.isEmpty()) return "缺 'query'（要点的按钮/条目文本，可部分匹配）。先 gui_dump 看当前屏幕。";
                    return g.clickByText(q);
                }
                case "gui_type": {
                    com.happyagent.mobile.service.GuardService g =
                            com.happyagent.mobile.service.GuardService.getInstance();
                    if (g == null) return guiNotEnabled();
                    String t = str(args, "text", "input", "value");
                    if (t.isEmpty()) return "缺 'text'（要输入的内容）。";
                    return g.typeText(t);
                }
                case "shell_proot": {
                    com.happyagent.mobile.data.ProotEnv env =
                            com.happyagent.mobile.data.ProotEnv.get(
                                    com.happyagent.mobile.HappyAgentApplication.get());
                    String c = str(args, "command", "cmd", "shell");
                    if (c.isEmpty()) return "缺 'command'（要在容器里跑的命令，如 'apk add python3' 或 'echo hi'）。";
                    int timeout = safeInt(args, "timeout_ms", 30000);
                    return env.execInContainer(c, timeout);
                }
                case "proot_status": {
                    com.happyagent.mobile.data.ProotEnv env =
                            com.happyagent.mobile.data.ProotEnv.get(
                                    com.happyagent.mobile.HappyAgentApplication.get());
                    StringBuilder sb = new StringBuilder();
                    sb.append("架构 ").append(env.abiName());
                    sb.append(env.isReady() ? " | 已就绪" : " | 未就绪");
                    if (!env.isReady()) {
                        sb.append(" | 探活: ").append(env.probeExec());
                    }
                    return sb.toString();
                }
                case "proot_setup": {
                    com.happyagent.mobile.data.ProotEnv env =
                            com.happyagent.mobile.data.ProotEnv.get(
                                    com.happyagent.mobile.HappyAgentApplication.get());
                    if (env.isReady()) return "容器环境已就绪，可直接用 shell_proot 跑命令。";
                    return "容器环境未部署。请在「设置 → 容器环境」点「一键部署」：会自动多线程下载 proot + Alpine（国内镜像，支持断点续传），完成后自动探活。部署是异步的，稍后再问 proot_status。";
                }
                default:
                    return "未知工具 '" + name + "'。可用：file_read, file_write, file_list, file_info, file_exists, file_move, file_copy, file_zip, file_unzip, "
                            + "file_edit, file_append, file_delete, file_mkdir, file_find, file_grep, shell_exec, http_get, http_post, "
                            + "text_base64_encode, text_base64_decode, text_url_encode, text_url_decode, text_json_get, text_upper, text_lower, text_stats, text_calc, "
                            + "system_device_info, system_battery, system_storage, system_network, system_clipboard_get, system_clipboard_set, "
                            + "gui_dump, gui_click, gui_type, shell_proot, proot_status, proot_setup, time_now。";
            }
        } catch (Exception e) {
            return "工具出错: " + e.getMessage();
        }
    }

    // 参数键名宽容：主键缺失时按同义键顺序取值。optString 对数字/布尔也取到字符串形式，
    // 因此参数值类型不一致（数字、布尔等）也能容下。
    private String str(JSONObject o, String key, String... alts) {
        String v = o.optString(key, "").trim();
        if (v.length() > 0) return v;
        for (String a : alts) {
            v = o.optString(a, "").trim();
            if (v.length() > 0) return v;
        }
        return "";
    }

    // 取整型参数：缺省或非数字时用默认值
    private int safeInt(JSONObject o, String key, int def) {
        if (o == null || !o.has(key)) return def;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Exception e) {
                return def;
            }
        }
        return def;
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
            if (body.length() > 4096) {
                return "HTTP " + code + ": " + body.substring(0, 4096)
                        + "\n…(响应体已截断到 4096 字符；要全文可 http_get 指定具体子页或分段)";
            }
            return "HTTP " + code + ": " + body;
        } catch (Exception e) {
            return "http 出错: " + e.getMessage();
        }
    }

    // POST：可带请求体（按 application/json 发送）；空 body 则不带
    private String httpPost(String url, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (body != null && body.length() > 0) {
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) != -1 && bo.size() < 65536) bo.write(buf, 0, n);
            if (is != null) is.close();
            conn.disconnect();
            String respBody = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            return "HTTP " + code + ": " + respBody.substring(0, Math.min(4096, respBody.length()));
        } catch (Exception e) {
            return "http 出错: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private List<Message> recentWindow(List<Message> all, int n) {
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    // 温度越界收口：OpenAI/Gemini 只收 0~2.0，滑条走到 2000 也不会 400
    private static double clampTemp(double t, double cap) {
        return t < 0 ? 0 : (t > cap ? cap : t);
    }

    // 离线本地引擎：没 Key 不装懂语言，但内置的确定性小能力真实可用（计算/时间/设备/存储/文件清单）
    private String localFallback(String task, List<Message> history, List<Attachment> images) {
        String t = task == null ? "" : task.trim();
        String calc = tryCalc(t);
        if (calc != null) {
            return "本地引擎计算：\n" + calc
                    + "\n（离线模式：未配置 " + providerLabel() + " Key，只跑了内置确定性计算。）";
        }
        String low = t.toLowerCase();
        if (low.contains("时间") || low.contains("日期") || low.contains("几点") || low.contains("now")) {
            return "当前时间：" + timeNowLocal();
        }
        if (low.contains("存储") || low.contains("空间")) {
            return "存储情况：\n" + systemTools.storageInfo();
        }
        if (low.contains("电池") || low.contains("电量")) {
            return "电池状态：\n" + systemTools.batteryStatus();
        }
        if (low.contains("设备") || low.contains("型号") || low.contains("内存")) {
            return "设备信息：\n" + systemTools.deviceInfo();
        }
        if (low.contains("列") || low.contains("文件") || low.contains("目录")) {
            return "工作区文件：\n" + fileTools.list(fileTools.getWorkspace());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前是离线本地模式（未配置 ").append(providerLabel()).append(" Key），没有语言理解能力。");
        sb.append("不过内置的确定性小能力现在就能用：\n");
        sb.append("① 算数：直接发表达式，如 128*37+9\n");
        sb.append("② 查时间：「现在几点了」\n");
        sb.append("③ 查设备/存储/电池：「查看存储」\n");
        sb.append("④ 列文件：「列出文件」\n");
        sb.append("更复杂的任务请先在「配置」页填 ").append(providerLabel())
                .append(" 的 API Key，我才能真正理解并调用工具完成。");
        if (images != null && !images.isEmpty()) {
            sb.append("\n（已收到 ").append(images.size()).append(" 个附件：");
            for (Attachment a : images) sb.append(a.fileName).append(' ');
            sb.append("，已存入工作区，配好 Key 后可直接处理。）");
        }
        return sb.toString();
    }

    // 文本整体是算术表达式（只含数字/运算符/括号/空白）才本地算；否则返回 null 不误判
    private String tryCalc(String t) {
        String s = t.trim().replace("?", "").replace("？", "");
        if (s.length() < 2 || s.length() > 60) return null;
        boolean hasOp = false, ok = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '(' || c == ')') continue;
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == ' ') { hasOp = true; continue; }
            ok = false;
            break;
        }
        if (!ok || !hasOp) return null;
        String r = TextTools.calc(s);
        if (r.startsWith("无法计算")) return null;
        return s + " = " + r;
    }

    private String timeNowLocal() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE",
                java.util.Locale.CHINA).format(new java.util.Date());
    }

    private String providerLabel() {
        switch (cfg.getProvider()) {
            case Config.PROVIDER_GOOGLE:   return "Google";
            case Config.PROVIDER_ANTHROPIC: return "Anthropic";
            default:                        return "OpenAI";
        }
    }

    // GUI 工具未开启无障碍服务时的诚实提示（不做假实现）
    private String guiNotEnabled() {
        return "GUI 自动化工具需要先在系统「设置 → 无障碍 → Happy Agent」里开启无障碍服务。"
                + "当前未开启，无法读取屏幕/点按/输入。开启后 gui_dump / gui_click / gui_type 才能工作。";
    }
}
