package com.happyagent.mobile.tools;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;

// 轻量级 Markdown → Spanned 渲染器（纯 Java + Android span，API 23 安全，无第三方依赖）。
// 支持：围栏代码块(带语言标签) / 行内代码 / 粗体 / 斜体 / 链接 / 标题 / 引用 / 列表 / 分隔线。
// 用于聊天 AI 气泡；按当前主题强调色上色，随 App 主题切换。
public final class Markdown {

    private Markdown() {}

    // 主题色板：从当前 App 主题解析，随强调色切换
    public static final class Palette {
        int heading, link, dim, list, codeBg, codeFg, codeLabel;
    }

    public static Palette palette(Context ctx) {
        int[] attrs = {
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorSurfaceVariant,
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
        };
        TypedArray a = ctx.getTheme().obtainStyledAttributes(attrs);
        try {
            Palette p = new Palette();
            p.heading = a.getColor(0, Color.BLACK);
            p.link = a.getColor(1, Color.BLACK);
            p.dim = a.getColor(2, Color.GRAY);
            p.list = a.getColor(3, Color.BLACK);
            p.codeBg = a.getColor(4, 0xFFF0EFEB);
            p.codeFg = a.getColor(5, Color.BLACK);
            p.codeLabel = a.getColor(6, Color.GRAY);
            return p;
        } finally {
            a.recycle();
        }
    }

    // 渲染整段 Markdown；返回 Spanned（可直接给 TextView.setText）。空输入返回空串。
    public static CharSequence render(Context ctx, String src) {
        if (src == null || src.trim().isEmpty()) return "";
        Palette p = palette(ctx);
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.split("\n");
        int n = lines.length;
        int i = 0;
        while (i < n) {
            String raw = lines[i];
            String t = raw;
            // 围栏代码块：```lang ... ```
            if (t.trim().startsWith("```")) {
                String lang = t.trim().substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < n) i++; // 跳过闭合围栏
                appendCodeBlock(out, code, lang, p);
                out.append('\n');
                continue;
            }
            // 标题：# / ## / ###
            int hashes = 0;
            while (hashes < t.length() && t.charAt(hashes) == '#') hashes++;
            String rest = hashes > 0 ? t.substring(hashes).trim() : t.trim();
            // 分隔线
            if (hashes == 0 && isHr(rest)) {
                SpannableStringBuilder hr = new SpannableStringBuilder("——————————————");
                int st = 0;
                hr.setSpan(new ForegroundColorSpan(p.dim), st, hr.length(), 0);
                hr.setSpan(new RelativeSizeSpan(0.8f), st, hr.length(), 0);
                out.append(hr);
                out.append("\n\n");
                i++;
                continue;
            }
            if (hashes > 0) {
                float size = hashes <= 1 ? 1.3f : (hashes == 2 ? 1.15f : 1.05f);
                SpannableStringBuilder h = new SpannableStringBuilder();
                h.append(inline(ctx, rest, p));
                int st = 0;
                h.setSpan(new StyleSpan(Typeface.BOLD), st, h.length(), 0);
                h.setSpan(new RelativeSizeSpan(size), st, h.length(), 0);
                h.setSpan(new ForegroundColorSpan(p.heading), st, h.length(), 0);
                out.append(h);
                out.append("\n");
                i++;
                continue;
            }
            // 引用
            if (raw.trim().startsWith(">")) {
                String content = raw.trim();
                content = content.substring(1);
                if (content.startsWith(" ")) content = content.substring(1);
                SpannableStringBuilder q = new SpannableStringBuilder();
                int bar = q.length();
                q.append("│ ");
                q.setSpan(new ForegroundColorSpan(p.dim), bar, bar + 1, 0);
                q.append(inline(ctx, content, p));
                int from = bar + 2;
                q.setSpan(new ForegroundColorSpan(p.dim), from, q.length(), 0);
                out.append(q);
                out.append('\n');
                i++;
                continue;
            }
            // 列表项
            int lead = leadingListMarker(raw);
            if (lead > 0) {
                String marker = raw.substring(0, lead);
                String item = raw.substring(lead).trim();
                SpannableStringBuilder lb = new SpannableStringBuilder();
                int st = 0;
                lb.append(marker);
                lb.setSpan(new ForegroundColorSpan(p.list), st, lb.length(), 0);
                lb.append(inline(ctx, item, p));
                out.append(lb);
                out.append('\n');
                i++;
                continue;
            }
            // 空行
            if (rest.length() == 0) {
                out.append('\n');
                i++;
                continue;
            }
            // 普通段落
            out.append(inline(ctx, rest, p));
            out.append("\n\n");
            i++;
        }
        // 去掉末尾多余空行
        while (out.length() > 1 && out.charAt(out.length() - 1) == '\n'
                && out.charAt(out.length() - 2) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    // 代码块：语言标签（小字强调色）+ 等宽正文（浅底，随主题）
    private static void appendCodeBlock(SpannableStringBuilder out, StringBuilder code,
                                        String lang, Palette p) {
        SpannableStringBuilder cb = new SpannableStringBuilder();
        if (lang.length() > 0) {
            int st = cb.length();
            cb.append(lang);
            cb.setSpan(new StyleSpan(Typeface.BOLD), st, cb.length(), 0);
            cb.setSpan(new ForegroundColorSpan(p.codeLabel), st, cb.length(), 0);
            cb.setSpan(new RelativeSizeSpan(0.78f), st, cb.length(), 0);
            cb.append('\n');
        }
        int codeStart = cb.length();
        cb.append(code.length() > 0 && code.charAt(code.length() - 1) == '\n'
                ? code.toString() : code.toString() + '\n');
        int codeEnd = cb.length();
        cb.setSpan(new TypefaceSpan("monospace"), codeStart, codeEnd, 0);
        cb.setSpan(new ForegroundColorSpan(p.codeFg), codeStart, codeEnd, 0);
        cb.setSpan(new BackgroundColorSpan(p.codeBg), codeStart, codeEnd, 0);
        cb.setSpan(new RelativeSizeSpan(0.9f), codeStart, codeEnd, 0);
        out.append(cb);
    }

    private static boolean isHr(String s) {
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '=' && c != '_') return false;
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    // 返回行首列表标记（含其后空格）的长度；不是列表返回 0
    private static int leadingListMarker(String s) {
        if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")
                || s.startsWith("• ") || s.startsWith("· ")) return 2;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i > 0 && i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == ')')) {
            int sp = i + 1;
            while (sp < s.length() && s.charAt(sp) == ' ') sp++;
            if (sp >= s.length()) return 0;
            return sp;
        }
        return 0;
    }

    // 单行内联：行内代码 / 粗体 / 斜体 / 链接。返回该行 Spanned（局部偏移，追加进上层时自动平移）。
    private static Spanned inline(Context ctx, String s, Palette p) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '`') {
                int close = s.indexOf('`', i + 1);
                if (close < 0) {
                    b.append('`');
                    i++;
                    continue;
                }
                int st = b.length();
                b.append(s.substring(i + 1, close));
                b.setSpan(new TypefaceSpan("monospace"), st, b.length(), 0);
                b.setSpan(new ForegroundColorSpan(p.codeFg), st, b.length(), 0);
                b.setSpan(new BackgroundColorSpan(p.codeBg), st, b.length(), 0);
                b.setSpan(new RelativeSizeSpan(0.9f), st, b.length(), 0);
                i = close + 1;
            } else if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("**", i + 2);
                if (close < 0) {
                    b.append("*");
                    i++;
                    continue;
                }
                int st = b.length();
                b.append(s.substring(i + 2, close));
                b.setSpan(new StyleSpan(Typeface.BOLD), st, b.length(), 0);
                i = close + 2;
            } else if (c == '*' || c == '_') {
                char tag = c;
                int close = -1;
                for (int j = i + 1; j < n; j++) {
                    if (s.charAt(j) == tag) { close = j; break; }
                }
                if (close < 0 || close == i + 1) {
                    b.append(c);
                    i++;
                    continue;
                }
                int st = b.length();
                b.append(s.substring(i + 1, close));
                b.setSpan(new StyleSpan(Typeface.ITALIC), st, b.length(), 0);
                i = close + 1;
            } else if (c == '[') {
                int rb = s.indexOf(']', i + 1);
                if (rb < 0 || rb + 1 >= n || s.charAt(rb + 1) != '(') {
                    b.append('[');
                    i++;
                    continue;
                }
                int rp = s.indexOf(')', rb + 2);
                if (rp < 0) {
                    b.append(s.substring(i));
                    i = n;
                    continue;
                }
                String label = s.substring(i + 1, rb);
                String url = s.substring(rb + 2, rp);
                int st = b.length();
                b.append(label);
                b.setSpan(new URLSpan(safeUrl(url)), st, b.length(), 0);
                b.setSpan(new UnderlineSpan(), st, b.length(), 0);
                b.setSpan(new ForegroundColorSpan(p.link), st, b.length(), 0);
                i = rp + 1;
            } else {
                b.append(c);
                i++;
            }
        }
        return b;
    }

    // URLSpan 只接受 http/https，防把 markdown 误当可点链接（点开的协议异常）
    private static String safeUrl(String u) {
        String t = u.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        if (t.startsWith("//")) return "https:" + t;
        return "https://" + t;
    }

    // 是否"看着像 Markdown"（含围栏/行内代码/标题/粗体/链接等）；像才走渲染，避免普通文本套等宽底
    public static boolean looksLikeMarkdown(String src) {
        if (src == null) return false;
        if (src.contains("```") || src.contains("`") || src.contains("](")
                || src.contains("**")) {
            return true;
        }
        String[] lines = src.split("\n");
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("#") || t.startsWith(">") || t.startsWith("- ")
                    || t.startsWith("* ") || t.startsWith("```")) {
                return true;
            }
        }
        return false;
    }
}
