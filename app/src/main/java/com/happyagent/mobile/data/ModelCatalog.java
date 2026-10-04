package com.happyagent.mobile.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.happyagent.mobile.model.Models.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// 上游模型目录：拉取真实模型列表、探测单模型上下文窗口、按模型名识别能力（图片/视频/工具/推理）。
//  三家端点：OpenAI 兼容 GET {base}/models（Bearer）/ Gemini GET /v1beta/models?key= /
//  Anthropic GET /v1/models（x-api-key）。后台线程执行，结果回主线程；
//  诚实报错（无 Key / 网络 / 非 JSON / HTTP 错），绝不编造假列表。API 23 安全。
public final class ModelCatalog {

    public interface Callback {
        void onResult(boolean ok, String errMsg, List<String> models);
    }

    // 单模型上下文窗口探测回调（ctx > 0 成功，-1 上游没给，走按名查表）
    public interface CtxCallback {
        void onResult(int ctx);
    }

    private ModelCatalog() {}

    // ============ 拉取模型列表 ============

    public static void fetch(Context ctx, final String provider, String baseUrl,
                             String key, final Callback cb) {
        final String k = key == null ? "" : key.trim();
        final Handler main = new Handler(Looper.getMainLooper());
        if (k.isEmpty()) {
            post(main, cb, false, "请先填写 API Key 再拉取", null);
            return;
        }
        final String base = baseUrl;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> models = doFetch(provider, base, k, true);
                    post(main, cb, true, null, models);
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? "网络异常" : e.getMessage();
                    post(main, cb, false, msg, null);
                }
            }
        }, "model-catalog").start();
    }

    // fetchUrl 传 false 时跳过 "/v1/models" → "/models" 的兜底换端（用于二次调用避免重复兜底）
    private static List<String> doFetch(String provider, String baseUrl, String key,
                                        boolean allowFallback) throws Exception {
        String url;
        String[] hdrs;
        if (Config.PROVIDER_GOOGLE.equals(provider)) {
            url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                    + URLEncoder.encode(key, "UTF-8");
            hdrs = new String[0];
        } else if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
            url = "https://api.anthropic.com/v1/models";
            hdrs = new String[]{"x-api-key", key, "anthropic-version", "2023-06-01"};
        } else {
            String base = (baseUrl == null || baseUrl.trim().isEmpty())
                    ? "https://api.openai.com/v1" : baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            url = base + "/models";
            hdrs = new String[]{"Authorization", "Bearer " + key};
        }

        String resp;
        int code;
        try {
            String[] r = httpGet(url, hdrs);
            code = Integer.parseInt(r[0]);
            resp = r[1];
        } catch (Exception netErr) {
            // 网络层失败：重试一次（1s 后），仍失败才抛
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            String[] r = httpGet(url, hdrs);
            code = Integer.parseInt(r[0]);
            resp = r[1];
        }

        // 自建网关常见 "/v1/models" 404 → 退到根 "/models" 再试一次
        if ((code == 404 || code == 405) && allowFallback
                && url.endsWith("/v1/models")) {
            String[] r = httpGet(url.substring(0, url.length() - "/v1/models".length()) + "/models", hdrs);
            code = Integer.parseInt(r[0]);
            resp = r[1];
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + "：" + resp.substring(0, Math.min(200, resp.length())));
        }

        return parseModels(provider, resp);
    }

    // 解析各家模型清单：OpenAI {data:[{id}]} / Anthropic {data|models:[{id,display_name}]} /
    // Google {models:[{name,displayName,supportedGenerationMethods}]}（只留能 generateContent 的）
    static List<String> parseModels(String provider, String resp) throws Exception {
        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.optJSONArray("data");
        if (arr == null) arr = json.optJSONArray("models");
        if (arr == null) throw new Exception("上游响应里没有模型清单（非兼容端点？）");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", o.optString("name", ""));
            if (id.isEmpty()) continue;
            if (Config.PROVIDER_GOOGLE.equals(provider)) {
                int p = id.lastIndexOf('/');
                if (p >= 0) id = id.substring(p + 1);   // models/gemini-2.5-flash → gemini-2.5-flash
                // 只保留支持 generateContent 的对话模型（过滤 embedding/list 等端点能力）
                JSONArray methods = o.optJSONArray("supportedGenerationMethods");
                if (methods != null) {
                    boolean gen = false;
                    for (int j = 0; j < methods.length(); j++) {
                        if ("generateContent".equals(methods.optString(j))) { gen = true; break; }
                    }
                    if (!gen) continue;
                }
            }
            // 显示名（display_name / displayName）跟在 id 后用 | 分隔存
            String display = o.optString("display_name", o.optString("displayName", ""));
            if (!display.isEmpty() && !display.equals(id)) out.add(id + "|" + display);
            else out.add(id);
        }
        // 短 id 靠前，常用模型更好找
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Integer.compare(idOf(a).length(), idOf(b).length());
                return c != 0 ? c : idOf(a).compareTo(idOf(b));
            }
        });
        return out;
    }

    private static String[] httpGet(String url, String[] hdrs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        for (int i = 0; i + 1 < hdrs.length; i += 2) conn.setRequestProperty(hdrs[i], hdrs[i + 1]);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);
        conn.disconnect();
        return new String[]{String.valueOf(code), resp};
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void post(final Handler main, final Callback cb, final boolean ok,
                             final String err, final List<String> models) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(ok, err, models);
            }
        });
    }

    // ============ 单模型上下文窗口探测（优先上游真实字段，失败走按名查表） ============

    // 同步版：给后台线程用。返回 >0 的真实上下文窗口，-1 表示上游没给。
    public static int detectContextSync(String provider, String model, String baseUrl, String key) {
        if (model == null || model.trim().isEmpty()) return -1;
        String m = model.trim();
        try {
            if (Config.PROVIDER_GOOGLE.equals(provider)) {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + URLEncoder.encode(m, "UTF-8") + "?key=" + URLEncoder.encode(key, "UTF-8");
                String resp = httpGet(url, new String[0])[1];
                JSONObject o = new JSONObject(resp);
                int v = o.optInt("inputTokenLimit", -1);
                if (v <= 0) v = o.optInt("input_token_limit", -1);
                return v > 0 ? v : -1;
            }
            if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
                String url = "https://api.anthropic.com/v1/models/" + URLEncoder.encode(m, "UTF-8");
                String resp = httpGet(url, new String[]{
                        "x-api-key", key, "anthropic-version", "2023-06-01"})[1];
                JSONObject o = new JSONObject(resp);
                int v = o.optInt("max_input_tokens", -1);
                if (v <= 0) v = o.optInt("context_window", -1);
                return v > 0 ? v : -1;
            }
            // OpenAI 兼容：标准 /v1/models/{id} 不带上下文字段，但 OpenRouter 等网关带 context_length
            String base = (baseUrl == null || baseUrl.trim().isEmpty())
                    ? "https://api.openai.com/v1" : baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String resp = httpGet(base + "/models/" + URLEncoder.encode(m, "UTF-8"),
                    new String[]{"Authorization", "Bearer " + key})[1];
            JSONObject o = new JSONObject(resp);
            int v = o.optInt("context_length", -1);
            if (v <= 0) v = o.optInt("contextLength", -1);
            if (v <= 0) v = o.optInt("max_input_tokens", -1);
            // OpenAI 兼容 data 数组形式
            if (v <= 0 && o.has("data")) {
                JSONObject d = o.optJSONObject("data");
                if (d != null) v = d.optInt("context_length", -1);
            }
            return v > 0 ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // 异步探测：先查缓存，缓存没有才发请求；结果回主线程（-1 = 没探到）。
    public static void detectContext(final Context ctx, final String provider, final String model,
                                     final String baseUrl, final String key, final CtxCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final String cacheKey = "detected_ctx_" + provider + "_" + model;
        final Prefs prefs = new Prefs(ctx.getApplicationContext());
        int cached = prefs.getInt(cacheKey, 0);
        if (cached > 0) {
            main.post(new Runnable() { @Override public void run() { cb.onResult(cached); } });
            return;
        }
        final String b = baseUrl;
        final String k = key == null ? "" : key;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int v = detectContextSync(provider, model, b, k);
                if (v > 0) prefs.putInt(cacheKey, v);
                main.post(new Runnable() { @Override public void run() { cb.onResult(v); } });
            }
        }, "ctx-detect").start();
    }

    // ============ 模型能力识别（按模型名查表：图片输入/生成、视频、工具、推理） ============

    public static final class Caps {
        public final boolean inImage;    // 能不能"看图"（输入图片）
        public final boolean outImage;   // 能不能生成图片
        public final boolean inVideo;    // 能不能看视频
        public final boolean tool;       // 会不会调工具
        public final boolean reasoning;  // 是不是推理模型
        Caps(boolean a, boolean b, boolean c, boolean d, boolean e) {
            inImage = a; outImage = b; inVideo = c; tool = d; reasoning = e;
        }
    }

    public static Caps capsOf(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        boolean inImg = false, outImg = false, vid = false, tool = true, reason = false;
        // 图片输入（视觉理解）
        if (m.contains("gpt-4o") || m.contains("gpt-4.1") || m.contains("gpt-5")
                || m.startsWith("o3") || m.startsWith("o4")
                || m.contains("claude")
                || m.contains("gemini")
                || m.contains("qwen-vl") || m.contains("qwen2-vl") || m.contains("qwen3-vl")
                || m.contains("vision") || m.contains("-vl") || m.contains("vl-")
                || m.contains("glm-4v") || m.contains("llava") || m.contains("pixtral")
                || m.contains("internvl") || m.contains("glm-4.5v")) inImg = true;
        // 图片生成
        if (m.contains("dall-e") || m.contains("gpt-image") || m.contains("imagen")
                || m.contains("flux") || m.contains("stable-diffusion") || m.contains("sdxl")
                || m.contains("image")) outImg = true;
        // 视频输入
        if (m.contains("gemini-2") || m.contains("gemini-1.5-pro") || m.contains("video")) vid = true;
        // 推理模型
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("o5")
                || m.contains("deepseek-r1") || m.contains("-r1") || m.contains("qwq")
                || m.contains("thinking") || m.contains("reasoner")) reason = true;
        // 纯模态端点不会调工具
        if (m.contains("embedding") || m.contains("whisper") || m.contains("tts")
                || m.contains("dall-e") || m.contains("imagen") || m.contains("moderation")
                || m.contains("audio")) tool = false;
        return new Caps(inImg, outImg, vid, tool, reason);
    }

    // 能力徽章摘要："图片输入 · 图片生成 · 视频 · 工具 · 推理"（没有则返回空串）
    public static String capsLabel(String model) {
        Caps c = capsOf(model);
        StringBuilder sb = new StringBuilder();
        if (c.inImage) append(sb, "图片输入");
        if (c.outImage) append(sb, "图片生成");
        if (c.inVideo) append(sb, "视频");
        if (c.tool) append(sb, "工具");
        if (c.reasoning) append(sb, "推理");
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }

    // ============ 模型上下文上限（按模型名兜底识别，接口探测优先） ============

    // 返回该模型的上下文窗口（token 数）。识别不了给 128K 保守默认。
    public static int contextLimitOf(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return 128_000;
        // GPT 家族
        if (m.contains("gpt-4.1")) return 1_047_576;          // 4.1 全系 1M
        if (m.contains("gpt-4o")) return 128_000;
        if (m.contains("gpt-4-turbo") || m.contains("gpt-4-")) return 128_000;
        if (m.contains("gpt-3.5")) return 16_385;
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return 200_000;
        // Claude 家族
        if (m.contains("claude")) return 200_000;
        // Gemini 家族
        if (m.contains("gemini-1.5-pro")) return 2_097_152;
        if (m.contains("gemini-1.5")) return 1_048_576;
        if (m.contains("gemini-2.5")) return 1_048_576;
        if (m.contains("gemini-2.0")) return 1_048_576;
        if (m.contains("gemini")) return 1_048_576;
        // DeepSeek
        if (m.contains("deepseek")) return 65_536;
        // 常见开源/国产（保守值）
        if (m.contains("qwen") || m.contains("qwq")) return 131_072;
        if (m.contains("kimi") || m.contains("moonshot")) return 131_072;
        if (m.contains("glm")) return 131_072;
        if (m.contains("doubao")) return 128_000;
        if (m.contains("llama")) return 131_072;
        if (m.contains("mistral") || m.contains("codestral")) return 131_072;
        if (m.contains("grok")) return 131_072;
        return 128_000;
    }

    // 生效上下文上限：上游实测缓存优先，其次按名查表（token 环、用量弹窗用）
    public static int effectiveContextLimit(Context ctx, String provider, String model) {
        int cached = new Prefs(ctx.getApplicationContext())
                .getInt("detected_ctx_" + provider + "_" + model, 0);
        return cached > 0 ? cached : contextLimitOf(model);
    }

    // 上下文上限的人读串（128K / 1M / 2M）
    public static String contextLabel(String model) {
        int n = contextLimitOf(model);
        if (n >= 1_000_000) {
            double mk = n / 1_048_576.0;
            return (mk >= 1 && Math.abs(mk - Math.round(mk)) < 0.01)
                    ? Math.round(mk) + "M" : String.format(Locale.ROOT, "%.1fM", mk);
        }
        if (n >= 1000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    // 大数字的人读串（1048576 → 1M）
    public static String tokensLabel(int n) {
        if (n <= 0) return "—";
        if (n >= 1_000_000) {
            double mk = n / 1_048_576.0;
            return (Math.abs(mk - Math.round(mk)) < 0.01)
                    ? Math.round(mk) + "M" : String.format(Locale.ROOT, "%.1fM", mk);
        }
        if (n >= 1000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    // ============ 拉取结果 / 模型池持久化（存 Prefs，聊天页切换模型也能拿到） ============

    // 行格式：纯 id 或 "id|显示名"
    public static void saveFetched(Context ctx, String provider, List<String> models) {
        if (models == null) return;
        new Prefs(ctx.getApplicationContext())
                .putString("fetched_models_" + provider, join(models));
    }

    public static List<String> loadFetched(Context ctx, String provider) {
        List<String> out = new ArrayList<String>();
        for (String line : loadRaw(ctx, provider, "fetched_models_")) out.add(idOf(line));
        return out;
    }

    // 行格式（含显示名），列表弹窗用
    public static List<String> loadFetchedRows(Context ctx, String provider) {
        return loadRaw(ctx, provider, "fetched_models_");
    }

    // 用户已导入的模型池（多选导入的产物）；聊天页模型切换优先用它
    public static void savePool(Context ctx, String provider, List<String> models) {
        if (models == null) return;
        new Prefs(ctx.getApplicationContext())
                .putString("pool_models_" + provider, join(models));
    }

    public static List<String> loadPool(Context ctx, String provider) {
        List<String> out = new ArrayList<String>();
        for (String line : loadRaw(ctx, provider, "pool_models_")) out.add(idOf(line));
        return out;
    }

    private static List<String> loadRaw(Context ctx, String provider, String keyPrefix) {
        String raw = new Prefs(ctx.getApplicationContext())
                .getString(keyPrefix + provider, "");
        List<String> out = new ArrayList<String>();
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    private static String join(List<String> models) {
        StringBuilder sb = new StringBuilder();
        for (String m : models) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m);
        }
        return sb.toString();
    }

    // "id|显示名" → id
    public static String idOf(String row) {
        if (row == null) return "";
        int p = row.indexOf('|');
        return p >= 0 ? row.substring(0, p) : row;
    }

    // "id|显示名" → 显示名（没有则 id）
    public static String displayOf(String row) {
        if (row == null) return "";
        int p = row.indexOf('|');
        return p >= 0 && p + 1 < row.length() ? row.substring(p + 1) : row;
    }
}
