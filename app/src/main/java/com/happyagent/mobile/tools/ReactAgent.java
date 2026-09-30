package com.happyagent.mobile.tools;

import com.happyagent.mobile.model.Models.Message;
import com.happyagent.mobile.model.Models.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// 多步 ReAct agent 循环：think → act(tool) → observe → think → ... → answer
// 对应 Operit 的 PhoneAgent while(step < maxSteps) 设计
// 有 OpenAI Key 时走真 LLM 驱动多步工具调用，没 Key 时降级单步本地模拟
public final class ReactAgent {

    private static final int MAX_STEPS = 10;
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;

    private final Config cfg;
    private final FileTools fileTools;
    private final ShellExecutor shell;
    private final List<Message> trace;

    public ReactAgent(Config cfg, FileTools ft, ShellExecutor se, List<Message> trace) {
        this.cfg = cfg;
        this.fileTools = ft;
        this.shell = se;
        this.trace = trace;
    }

    // 跑多步循环，返回最终答案
    public String run(String task) {
        // 系统提示：告诉模型它能用什么工具
        String systemPrompt = buildSystemPrompt();
        trace.add(new Message("system", "PLAN: " + task, System.currentTimeMillis()));

        // 如果没配 OpenAI Key，走本地单步模拟（和之前一样）
        if (!cfg.hasOpenAIKey()) {
            return localFallback(task);
        }

        // 多步循环
        List<JSONObject> messages = new ArrayList<JSONObject>();
        addMsg(messages, "system", systemPrompt);
        addMsg(messages, "user", task);

        for (int step = 1; step <= MAX_STEPS; step++) {
            trace.add(new Message("system", "STEP " + step + "/ " + MAX_STEPS, System.currentTimeMillis()));
            try {
                String llmResponse = callLlm(messages);
                JSONObject parsed = parseLlmResponse(llmResponse);

                // 看模型是给了工具调用还是最终答案
                if (parsed.has("tool_call")) {
                    JSONObject tc = parsed.getJSONObject("tool_call");
                    String toolName = tc.getString("name");
                    JSONObject args = tc.has("arguments") ? tc.getJSONObject("arguments") : new JSONObject();

                    String result = dispatchTool(toolName, args);
                    trace.add(new Message("tool", toolName + " -> " + result, System.currentTimeMillis()));

                    // 把工具结果喂回去，让模型继续
                    addMsg(messages, "assistant", llmResponse);
                    addMsg(messages, "user", "Tool [" + toolName + "] returned: " + result + ". Continue.");
                } else {
                    // 模型认为任务完成，返回最终答案
                    String answer = parsed.optString("answer", llmResponse);
                    trace.add(new Message("assistant", answer, System.currentTimeMillis()));
                    return answer;
                }
            } catch (Exception e) {
                trace.add(new Message("system", "Step " + step + " error: " + e.getMessage(),
                        System.currentTimeMillis()));
                return "Agent stopped at step " + step + ": " + e.getMessage();
            }
        }
        return "Reached max steps (" + MAX_STEPS + "). Last trace:\n" + trace.get(trace.size() - 1).text;
    }

    // 分发工具调用
    private String dispatchTool(String name, JSONObject args) {
        try {
            switch (name) {
                case "file_read":
                    return fileTools.read(args.getString("path"));
                case "file_write":
                    return fileTools.write(args.getString("path"), args.getString("content"));
                case "file_list":
                    return fileTools.list(args.optString("path", fileTools.getWorkspace()));
                case "file_find":
                    return fileTools.find(args.optString("path", fileTools.getWorkspace()),
                            args.getString("name"));
                case "file_grep":
                    return fileTools.grep(args.getString("path"), args.getString("keyword"));
                case "file_info":
                    return fileTools.info(args.optString("path", fileTools.getWorkspace()));
                case "shell_exec":
                    int timeout = args.has("timeout_ms") ? args.getInt("timeout_ms") : 15000;
                    return shell.exec(args.getString("command"), timeout);
                case "shell_detect":
                    return shell.detectProot();
                case "http_get":
                    return httpGet(args.getString("url"));
                case "memory_recall":
                    return "workspace: " + fileTools.getWorkspace();
                default:
                    return "unknown tool: " + name;
            }
        } catch (Exception e) {
            return "tool error: " + e.getMessage();
        }
    }

    // 构建系统提示（告诉模型可用的工具）
    private String buildSystemPrompt() {
        return "You are an AI agent running on an Android device. "
                + "You can use tools to complete tasks. "
                + "Available tools (respond with JSON):\n"
                + "{\"tool_call\": {\"name\": \"<tool>\", \"arguments\": {...}}} for tool calls, or\n"
                + "{\"answer\": \"<final answer>\"} when done.\n\n"
                + "Tools:\n"
                + "- file_read: {\"path\": \"<path>\"}\n"
                + "- file_write: {\"path\": \"<path>\", \"content\": \"<text>\"}\n"
                + "- file_list: {\"path\": \"<dir>\"}\n"
                + "- file_find: {\"path\": \"<dir>\", \"name\": \"<filename>\"}\n"
                + "- file_grep: {\"path\": \"<file>\", \"keyword\": \"<text>\"}\n"
                + "- file_info: {\"path\": \"<path>\"}\n"
                + "- shell_exec: {\"command\": \"<cmd>\", \"timeout_ms\": 15000}\n"
                + "- shell_detect: {}\n"
                + "- http_get: {\"url\": \"<url>\"}\n"
                + "- memory_recall: {}\n\n"
                + "Workspace: " + fileTools.getWorkspace() + "\n"
                + "You have up to " + MAX_STEPS + " steps. Think step by step.";
    }

    // 调 OpenAI /chat/completions（用 function calling 风格的 prompt）
    private String callLlm(List<JSONObject> messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", cfg.temperature / 100.0);
        body.put("max_tokens", cfg.maxTokens);
        // messages 是 List<JSONObject>，JSONArray 不能直接 put，手动拼
        JSONArray arr = new JSONArray();
        for (JSONObject m : messages) arr.put(m);
        body.put("messages", arr);

        String base = cfg.openaiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Authorization", "Bearer " + cfg.openaiKey.trim());
        conn.setRequestProperty("Content-Type", "application/json");

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        conn.disconnect();
        String resp = new String(bo.toByteArray(), StandardCharsets.UTF_8);

        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + resp);

        JSONObject jo = new JSONObject(resp);
        JSONArray choices = jo.getJSONArray("choices");
        return choices.getJSONObject(0).getJSONObject("message").getString("content");
    }

    private void addMsg(List<JSONObject> msgs, String role, String content) {
        try {
            JSONObject m = new JSONObject();
            m.put("role", role);
            m.put("content", content);
            msgs.add(m);
        } catch (Exception ignored) {}
    }

    // 解析模型返回（可能是 JSON 或纯文本）
    private JSONObject parseLlmResponse(String raw) {
        try {
            // 尝试直接解析
            return new JSONObject(raw);
        } catch (Exception e) {
            // 从文本里找 JSON 块
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return new JSONObject(raw.substring(start, end + 1));
                } catch (Exception e2) {
                    // 纯文本答案
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

    // 降级：没 Key 时单步本地模拟
    private String localFallback(String task) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n");
        sb.append("Local engine (no API key configured)\n");
        sb.append("Steps completed: 1\n");
        sb.append("Result: task acknowledged in local mode.\n");
        return sb.toString();
    }

    // 简单 HTTP GET
    private String httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = is.read(buf)) != -1 && bo.size() < 65536) bo.write(buf, 0, n);
            is.close();
            conn.disconnect();
            return "HTTP " + code + ": " + new String(bo.toByteArray(), StandardCharsets.UTF_8).substring(0, Math.min(4096, bo.size()));
        } catch (Exception e) {
            return "http error: " + e.getMessage();
        }
    }
}
