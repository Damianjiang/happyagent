package com.happyagent.mobile.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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

// 模型选择弹窗：预设 + 已拉取的真实模型合成一张列表（带上下文上限标注），
// 支持搜索过滤 / 一键重新拉取上游 / 手动输入自定义模型名。配置页与聊天页共用。
public final class ModelListDialog {

    public interface OnPick {
        void onPick(String model);
    }

    private ModelListDialog() {}

    public static void show(final Context ctx, final String current, final OnPick cb) {
        final Config c = AgentBackend.get().getConfig();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int d = (int) ctx.getResources().getDisplayMetrics().density;
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
                LinearLayout.LayoutParams.MATCH_PARENT, 420 * d));

        // 预设 + 已拉取合成、去重
        final List<String> all = new ArrayList<String>();
        final LinkedHashSet<String> seen = new LinkedHashSet<String>();
        java.util.List<String> presets = presetsFor(c.getProvider());
        java.util.List<String> fetched =
                ModelCatalog.loadFetched(ctx, c.getProvider());
        String cur = current == null ? "" : current;
        if (!cur.isEmpty()) seen.add(cur);          // 当前模型置顶
        for (String m : presets) if (seen.add(m)) all.add(m);
        for (String m : fetched) if (seen.add(m)) all.add(m);
        final List<String> models = new ArrayList<String>();
        if (!cur.isEmpty()) models.add(cur);
        models.addAll(all);

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                list.removeAllViews();
                int shown = 0;
                for (final String m : models) {
                    if (!q.isEmpty() && !m.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                    shown++;
                    list.addView(row(ctx, m, m.equals(current), d, () -> {
                        cb.onPick(m);
                    }));
                }
                if (shown == 0) {
                    TextView none = new TextView(ctx);
                    none.setText("没有匹配的模型，可点下方「手动输入」");
                    none.setTextSize(13);
                    none.setPadding(0, 16 * d, 0, 0);
                    none.setTextColor(androidx.core.content.ContextCompat.getColor(
                            ctx, R.color.on_surface_variant));
                    list.addView(none);
                }
                status.setText("共 " + models.size() + " 个可选（上下文按模型自动识别）");
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { render[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        render[0].run();

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("选择模型")
                .setView(box)
                .setPositiveButton("手动输入…", (d2, w) -> showManualInput(ctx, current, cb))
                .setNeutralButton("重新拉取", null)   // 监听器后绑，见下
                .setNegativeButton("取消", null)
                .show();
        // 重新拉取：拉成功刷新本弹窗列表（拉完自动重开一次，简单可靠）
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String base = Config.PROVIDER_GOOGLE.equals(c.getProvider())
                    ? "https://generativelanguage.googleapis.com"
                    : (Config.PROVIDER_ANTHROPIC.equals(c.getProvider())
                        ? "https://api.anthropic.com" : c.openaiBaseUrl);
            String key = c.apiKey();
            final android.app.ProgressDialog pd = android.app.ProgressDialog.show(
                    ctx, "", "正在拉取模型列表…", true, false);
            ModelCatalog.fetch(ctx, c.getProvider(), base, key, new ModelCatalog.Callback() {
                @Override
                public void onResult(boolean ok, String errMsg, List<String> mlist) {
                    pd.dismiss();
                    if (ok && mlist != null && !mlist.isEmpty()) {
                        ModelCatalog.saveFetched(ctx, c.getProvider(), mlist);
                        dlg.dismiss();
                        show(ctx, current, cb);   // 用新列表重开
                    } else {
                        status.setText(ok ? "上游暂无可用模型" : "拉取失败：" + errMsg);
                    }
                }
            });
        });
    }

    // 手动输入自定义模型名（上游列表没有的冷门模型也能填）
    private static void showManualInput(Context ctx, String current, OnPick cb) {
        final EditText input = new EditText(ctx);
        input.setHint("模型 id，如 gpt-4o-mini");
        input.setText(current == null ? "" : current);
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setPadding(48 * d, 16 * d, 48 * d, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(ctx)
                .setTitle("手动输入模型")
                .setView(wrap)
                .setPositiveButton("确定", (d2, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.isEmpty()) cb.onPick(v);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 列表行：模型名 + 右侧上下文上限 + 当前选中标记
    private static View row(Context ctx, String model, boolean selected, int d, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(4 * d, 12 * d, 4 * d, 12 * d);
        row.setClickable(true);
        row.setFocusable(true);
        row.setForeground(androidx.core.content.ContextCompat.getDrawable(
                ctx, android.R.drawable.list_selector_background));
        row.setOnClickListener(v -> onClick.run());

        TextView name = new TextView(ctx);
        name.setText(model);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface));
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView ctxLabel = new TextView(ctx);
        ctxLabel.setText(ModelCatalog.contextLabel(model) + " 上下文");
        ctxLabel.setTextSize(11);
        ctxLabel.setPadding(8 * d, 0, 8 * d, 0);
        ctxLabel.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface_variant));
        row.addView(ctxLabel);

        if (selected) {
            TextView check = new TextView(ctx);
            check.setText("✓");
            check.setTextSize(14);
            check.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.acc_default_c_on));
            row.addView(check);
        }
        return row;
    }

    private static List<String> presetsFor(String provider) {
        List<String> out = new ArrayList<String>();
        if (Config.PROVIDER_GOOGLE.equals(provider)) {
            out.add("gemini-2.5-flash");
            out.add("gemini-2.5-pro");
            out.add("gemini-2.0-flash");
            out.add("gemini-1.5-pro");
        } else if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
            out.add("claude-sonnet-4-6");
            out.add("claude-haiku-4-5");
            out.add("claude-opus-4-1");
        } else {
            out.add("gpt-4.1-mini");
            out.add("gpt-4.1");
            out.add("gpt-4o-mini");
            out.add("gpt-4o");
            out.add("o4-mini");
        }
        return out;
    }
}
