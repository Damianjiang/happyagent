package com.happyagent.mobile.tools;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// 文本 / 数据处理：base64、URL 编解码、JSON 取字段、大小写与统计。纯端侧，无外部依赖。
public final class TextTools {

    private TextTools() {}

    public static String base64Encode(String s) {
        byte[] data = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public static String base64Decode(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        byte[] raw;
        try {
            raw = Base64.decode(s.trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return "base64 解码失败（输入不是合法 base64）: " + e.getMessage();
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // 按点路径取 JSON 字段，如 "a.b.0.c"；根对象或数组均可；取不到返回空串
    public static String jsonGet(String json, String path) {
        if (json == null || json.trim().isEmpty()) return "(空)";
        String t = json.trim();
        Object cur;
        if (t.startsWith("[")) {
            try { cur = new JSONArray(t); } catch (Exception e) { return json; }
        } else {
            try { cur = new JSONObject(t); } catch (Exception e) { return json; } // 不是 JSON 原样返回，交给模型判断
        }
        if (path == null || path.trim().isEmpty()) return String.valueOf(cur);
        for (String key : path.trim().split("\\.")) {
            if (cur instanceof JSONObject) {
                JSONObject jo = (JSONObject) cur;
                cur = jo.has(key) ? jo.opt(key) : "";
            } else if (cur instanceof JSONArray) {
                int idx = parseIndex(key);
                JSONArray arr = (JSONArray) cur;
                if (idx >= 0 && idx < arr.length()) {
                    try { cur = arr.get(idx); } catch (Exception e) { cur = ""; }
                } else {
                    cur = "";
                }
            } else {
                return String.valueOf(cur);
            }
        }
        return cur == null ? "" : String.valueOf(cur);
    }

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String toUpperCase(String s) {
        return s == null ? "" : s.toUpperCase(Locale.US);
    }

    public static String toLowerCase(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    // 文本统计：字符 / 行 / 词
    public static String textStats(String s) {
        String t = s == null ? "" : s;
        int chars = t.length();
        int lines = 0;
        for (int i = 0; i < chars; i++) if (t.charAt(i) == '\n') lines++;
        if (chars > 0) lines++; // 末尾无换行也算一行
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < chars; i++) {
            char c = t.charAt(i);
            if (!Character.isWhitespace(c)) inWord = true;
            else if (inWord) { words++; inWord = false; }
        }
        if (inWord) words++;
        return "字符 " + chars + "，行 " + lines + "，词 " + words;
    }
}
