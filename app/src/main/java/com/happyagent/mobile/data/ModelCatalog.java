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

// 从上游供应商拉取真实模型列表（Operit 同款能力：模型管理"自动识别上游模型"）。
//  三家端点：OpenAI 兼容 GET {baseUrl}/models（Bearer）/ Gemini GET /v1beta/models?key= /
//  Anthropic GET /v1/models（x-api-key）。后台线程执行，结果回主线程；
//  诚实报错（无 Key / 网络 / 非 JSON / HTTP 错），绝不编造假列表。API 23 安全。
public final class ModelCatalog {

    public interface Callback {
        void onResult(boolean ok, String errMsg, List<String> models);
    }

    private ModelCatalog() {}

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
                    List<String> models = doFetch(provider, base, k);
                    post(main, cb, true, null, models);
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? "网络异常" : e.getMessage();
                    post(main, cb, false, msg, null);
                }
            }
        }, "model-catalog").start();
    }

    private static List<String> doFetch(String provider, String baseUrl, String key) throws Exception {
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

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        for (int i = 0; i + 1 < hdrs.length; i += 2) conn.setRequestProperty(hdrs[i], hdrs[i + 1]);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + "：" + resp.substring(0, Math.min(200, resp.length())));
        }

        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.optJSONArray("data");
        if (arr == null) arr = json.optJSONArray("models");
        if (arr == null) throw new Exception("上游响应里没有模型清单（非兼容端点？）");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", o.optString("name", ""));
            if (Config.PROVIDER_GOOGLE.equals(provider)) {
                int p = id.lastIndexOf('/');
                if (p >= 0) id = id.substring(p + 1);   // models/gemini-2.5-flash → gemini-2.5-flash
            }
            if (!id.isEmpty()) out.add(id);
        }
        // 短 id 靠前，常用模型更好找
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Integer.compare(a.length(), b.length());
                return c != 0 ? c : a.compareTo(b);
            }
        });
        return out;
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
}
