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

    // 算术求值：只支持数字、+ - * / % 与括号（四则+取模），不支持变量/函数，防注入。
    // 弱模型算数不可靠，给它一个确定性计算器。
    public static String calc(String expr) {
        if (expr == null || expr.trim().isEmpty()) return "空表达式";
        try {
            double v = parseExpr(expr.trim());
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                long li = (long) v;
                return String.valueOf(li);
            }
            return String.valueOf(v);
        } catch (Exception e) {
            return "无法计算: " + expr;
        }
    }

    private static double parseExpr(String s) {
        Parser p = new Parser(s);
        double v = p.additive();
        p.skip();
        if (!p.eof()) throw new RuntimeException("多余字符: '" + p.peek() + "'");
        return v;
    }

    // 递归下降：additive → additive (±) multiplicative；multiplicative → mul (/) unary；unary → ± unary | atom；atom → 数字 | ( additive )
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            skip();
            return i >= s.length();
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : ' ';
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean eat(char c) {
            skip();
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        double additive() {
            double v = multiplicative();
            while (true) {
                char c = peek();
                if (c == '+' && eat('+')) v += multiplicative();
                else if (c == '-' && eat('-')) v -= multiplicative();
                else return v;
            }
        }

        double multiplicative() {
            double v = unary();
            while (true) {
                char c = peek();
                if (c == '*' && eat('*')) v *= unary();
                else if (c == '/' && eat('/')) v /= unary();
                else if (c == '%' && eat('%')) v %= unary();
                else return v;
            }
        }

        double unary() {
            char c = peek();
            if (c == '+' && eat('+')) return unary();
            if (c == '-' && eat('-')) return -unary();
            return atom();
        }

        double atom() {
            if (eat('(')) {
                double v = additive();
                if (!eat(')')) throw new RuntimeException("缺少右括号");
                return v;
            }
            skip();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new RuntimeException("期望数字");
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
