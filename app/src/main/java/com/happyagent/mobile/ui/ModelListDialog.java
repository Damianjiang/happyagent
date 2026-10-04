package com.happyagent.mobile.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.ModelCatalog;
import com.happyagent.mobile.model.Models.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

// 模型选择弹窗，两种模式：
//  1) showImport —— 配置页多选导入：只显示真实拉取的上游模型，每行复选框，
//     支持搜索 / 全选 / 导入(N)；导入结果存模型池并把第一个设为当前模型。
//  2) showSingle —— 聊天页单选切换：优先模型池（导入结果），其次拉取缓存，点行即切。
// 每行带上下文上限与能力徽章（图片输入/图片生成/视频/工具/推理，按模型名自动识别）。
public final class ModelListDialog {

    public interface OnPick {
        void onPick(String model);
    }

    public interface OnImport {
        void onImport(List<String> models);
    }

    private ModelListDialog() {}

    // ==================== 多选导入（配置页） ====================

    public static void showImport(final Context ctx, final String current, final OnImport cb) {
        final Config c = AgentBackend.get().getConfig();
        final int d = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24 * d, 8 * d, 24 * d, 0);

        // 搜索 + 重新拉取（一行两列）
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final EditText search = new EditText(ctx);
        search.setHint("搜索模型…");
        search.setSingleLine(true);
        head.addView(search, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = new TextView(ctx);
        refresh.setText("重新拉取");
        refresh.setTextSize(13);
        refresh.setTextColor(ThemeUtil.attrColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer));
        refresh.setPadding(12 * d, 0, 0, 0);
        head.addView(refresh);
        box.addView(head);

        final TextView status = new TextView(ctx);
        status.setTextSize(12);
        status.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface_variant));
        status.setPadding(0, 6 * d, 0, 0);
        box.addView(status);

        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 440 * d));

        // 数据源：只放真实拉取结果（预置示例绝不混入）；当前手填模型若不在列表也补一行
        final List<String> rows = new ArrayList<String>(ModelCatalog.loadFetchedRows(ctx, c.getProvider()));
        final List<String> ids = new ArrayList<String>();
        for (String r : rows) ids.add(ModelCatalog.idOf(r));
        String cur = current == null ? "" : current;
        if (!cur.isEmpty() && !ids.contains(cur)) {
            rows.add(0, cur);
            ids.add(0, cur);
        }
        // 已选集合：默认 = 当前模型 + 模型池里已导入的
        final LinkedHashSet<String> selected = new LinkedHashSet<String>();
        if (!cur.isEmpty()) selected.add(cur);
        for (String m : ModelCatalog.loadPool(ctx, c.getProvider())) selected.add(m);
        selected.retainAll(ids);

        final TextView[] confirmBtn = new TextView[1];
        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                list.removeAllViews();
                int shown = 0;
                for (int i = 0; i < rows.size(); i++) {
                    final String row = rows.get(i);
                    final String id = ids.get(i);
                    if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
                    shown++;
                    list.addView(importRow(ctx, row, selected.contains(id), d, v -> {
                        if (selected.contains(id)) selected.remove(id);
                        else selected.add(id);
                        render[0].run();
                    }));
                }
                if (shown == 0) {
                    TextView none = new TextView(ctx);
                    none.setText(rows.isEmpty()
                            ? "还没有拉取到模型，点右上「重新拉取」从上游获取真实列表"
                            : "没有匹配的模型");
                    none.setTextSize(13);
                    none.setPadding(0, 16 * d, 0, 0);
                    none.setTextColor(androidx.core.content.ContextCompat.getColor(
                            ctx, R.color.on_surface_variant));
                    list.addView(none);
                }
                status.setText("上游模型 " + rows.size() + " 个 · 已选 " + selected.size() + " 个");
                if (confirmBtn[0] != null) {
                    confirmBtn[0].setText("导入(" + selected.size() + ")");
                }
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { render[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        render[0].run();

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("选择要导入的模型")
                .setView(box)
                .setPositiveButton("导入(0)", null)
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .show();
        confirmBtn[0] = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        confirmBtn[0].setText("导入(" + selected.size() + ")");
        confirmBtn[0].setEnabled(!selected.isEmpty());
        // 列表变化时同步可用态
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (selected.isEmpty()) return;
            List<String> out = new ArrayList<String>();
            for (String id : ids) if (selected.contains(id)) out.add(id);
            dlg.dismiss();
            cb.onImport(out);
        });
        // 全选 / 取消全选
        final TextView neutral = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        neutral.setOnClickListener(v -> {
            if (selected.size() >= ids.size()) {
                selected.clear();
                neutral.setText("全选");
            } else {
                selected.addAll(ids);
                neutral.setText("清空");
            }
            render[0].run();
        });
        // 重新拉取：拉完用新列表重开本弹窗
        refresh.setOnClickListener(v -> doRefresh(ctx, c, box, cb, current, status));
    }

    private static void doRefresh(final Context ctx, final Config c, View box,
                                  final OnImport cb, final String current, final TextView status) {
        String base;
        String key;
        if (Config.PROVIDER_GOOGLE.equals(c.getProvider())) {
            base = "https://generativelanguage.googleapis.com";
            key = c.googleKey;
        } else if (Config.PROVIDER_ANTHROPIC.equals(c.getProvider())) {
            base = "https://api.anthropic.com";
            key = c.anthropicKey;
        } else {
            base = c.openaiBaseUrl;
            key = c.openaiKey;
        }
        status.setText("正在从上游拉取真实模型列表…");
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(
                ctx, "", "正在拉取模型列表…", true, false);
        ModelCatalog.fetch(ctx, c.getProvider(), base, key, new ModelCatalog.Callback() {
            @Override
            public void onResult(boolean ok, String errMsg, List<String> mlist) {
                pd.dismiss();
                if (ok && mlist != null && !mlist.isEmpty()) {
                    ModelCatalog.saveFetched(ctx, c.getProvider(), mlist);
                    showImport(ctx, current, cb);   // 用新列表重开
                } else {
                    status.setText(ok ? "上游暂无可用模型" : "拉取失败：" + errMsg);
                }
            }
        });
    }

    // 导入模式行：复选框 + 模型名 + 能力徽章 + 上下文
    private static View importRow(Context ctx, String row, boolean checked, int d,
                                  View.OnClickListener toggle) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(2 * d, 8 * d, 2 * d, 8 * d);
        r.setClickable(true);
        r.setFocusable(true);
        r.setForeground(androidx.core.content.ContextCompat.getDrawable(
                ctx, android.R.drawable.list_selector_background));
        r.setOnClickListener(toggle);

        CheckBox cb = new CheckBox(ctx);
        cb.setChecked(checked);
        cb.setClickable(false);
        cb.setFocusable(false);
        r.addView(cb);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        String id = ModelCatalog.idOf(row);
        TextView name = new TextView(ctx);
        name.setText(id);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface));
        mid.addView(name);
        addBadges(ctx, mid, id, d, row);
        r.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    // ==================== 单选切换（聊天页） ====================

    public static void showSingle(final Context ctx, final String current, final OnPick cb) {
        final Config c = AgentBackend.get().getConfig();
        final int d = (int) ctx.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24 * d, 8 * d, 24 * d, 0);

        final EditText search = new EditText(ctx);
        search.setHint("搜索模型…");
        search.setSingleLine(true);
        box.addView(search);

        final TextView status = new TextView(ctx);
        status.setTextSize(12);
        status.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface_variant));
        status.setPadding(0, 6 * d, 0, 0);
        box.addView(status);

        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 440 * d));

        // 数据源：模型池（导入结果）优先，其次拉取缓存；当前模型兜底补一行
        final List<String> rows = new ArrayList<String>();
        List<String> pool = ModelCatalog.loadPool(ctx, c.getProvider());
        if (pool.isEmpty()) pool = ModelCatalog.loadFetched(ctx, c.getProvider());
        for (String m : pool) rows.add(m);
        String cur = current == null ? "" : current;
        if (!cur.isEmpty() && !rows.contains(cur)) rows.add(0, cur);

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                list.removeAllViews();
                int shown = 0;
                for (final String m : rows) {
                    if (!q.isEmpty() && !m.toLowerCase(Locale.ROOT).contains(q)) continue;
                    shown++;
                    list.addView(pickRow(ctx, m, m.equals(current), d, v -> {
                        cb.onPick(m);
                    }));
                }
                if (shown == 0) {
                    TextView none = new TextView(ctx);
                    none.setText(rows.isEmpty()
                            ? "暂无可用模型，去「模型接入」页拉取上游模型"
                            : "没有匹配的模型");
                    none.setTextSize(13);
                    none.setPadding(0, 16 * d, 0, 0);
                    none.setTextColor(androidx.core.content.ContextCompat.getColor(
                            ctx, R.color.on_surface_variant));
                    list.addView(none);
                }
                status.setText("可切换 " + rows.size() + " 个模型（点行即切）");
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { render[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        render[0].run();

        new AlertDialog.Builder(ctx)
                .setTitle("切换模型")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    // 单选行：模型名 + 徽章 + 当前勾
    private static View pickRow(Context ctx, String model, boolean current, int d,
                                View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(4 * d, 8 * d, 4 * d, 8 * d);
        r.setClickable(true);
        r.setFocusable(true);
        r.setForeground(androidx.core.content.ContextCompat.getDrawable(
                ctx, android.R.drawable.list_selector_background));
        r.setOnClickListener(onClick);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(model);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface));
        mid.addView(name);
        addBadges(ctx, mid, model, d, null);
        r.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (current) {
            TextView check = new TextView(ctx);
            check.setText("✓");
            check.setTextSize(16);
            check.setTextColor(ThemeUtil.attrColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer));
            r.addView(check);
        }
        return r;
    }

    // 行内徽章：上下文上限 + 能力标签（图片输入/图片生成/视频/工具/推理）
    private static void addBadges(Context ctx, LinearLayout mid, String id, int d, String row) {
        LinearLayout badges = new LinearLayout(ctx);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        badges.setPadding(0, 3 * d, 0, 0);

        String display = row == null ? null : ModelCatalog.displayOf(row);
        if (display != null && !display.equals(id)) {
            TextView disp = new TextView(ctx);
            disp.setText(display);
            disp.setTextSize(11);
            disp.setMaxEms(14);
            disp.setEllipsize(TextUtils.TruncateAt.END);
            disp.setSingleLine(true);
            disp.setTextColor(androidx.core.content.ContextCompat.getColor(
                    ctx, R.color.on_surface_variant));
            badges.addView(disp);
        }

        badge(ctx, badges, ModelCatalog.contextLabel(id) + " 上下文", d, false);
        if (ModelCatalog.capsOf(id).inImage) badge(ctx, badges, "图片输入", d, true);
        if (ModelCatalog.capsOf(id).outImage) badge(ctx, badges, "图片生成", d, true);
        if (ModelCatalog.capsOf(id).inVideo) badge(ctx, badges, "视频", d, true);
        if (ModelCatalog.capsOf(id).reasoning) badge(ctx, badges, "推理", d, true);

        mid.addView(badges);
    }

    private static void badge(Context ctx, LinearLayout parent, String text, int d, boolean accent) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(10);
        b.setPadding(6 * d, 1 * d, 6 * d, 1 * d);
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setTextColor(accent
                ? ThemeUtil.attrColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer)
                : androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 5 * d;
        parent.addView(b, lp);
    }
}
