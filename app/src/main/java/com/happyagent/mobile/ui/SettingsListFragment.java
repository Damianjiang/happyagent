package com.happyagent.mobile.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;

// 设置列表：纯文字行（不画花里胡哨的图标圆了，之前那些 shape 小尺寸渲染全糊）
// 每行 = 标题 + 副标题 + 右箭头，整行可点
public class SettingsListFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_settings_list, container, false);
        final Context ctx = requireContext();
        LinearLayout feature = v.findViewById(R.id.settings_list);
        LinearLayout tools = v.findViewById(R.id.tools_files_list);

        // 功能设置
        feature.addView(row(ctx, "模型接入", "供应商 · 模型 · 温度 · 最大步数",
                () -> startActivity(new Intent(ctx, ModelConfigActivity.class))));
        feature.addView(row(ctx, "外观 · 行为", "主题 · 强调色 · 字号 · 震动 · 朗读",
                () -> startActivity(settingsIntent(ctx, "外观"))));
        feature.addView(row(ctx, "提示词 / Skill", "自定义系统提示词 · 标签片段",
                () -> startActivity(settingsIntent(ctx, "提示词"))));
        feature.addView(row(ctx, "容器环境", "免 root Alpine 终端 · 一键部署",
                () -> startActivity(settingsIntent(ctx, "容器"))));
        feature.addView(row(ctx, "GUI 自动化", "无障碍服务状态",
                () -> startActivity(settingsIntent(ctx, "GUI"))));
        feature.addView(row(ctx, "Web 服务", "电脑/手机浏览器访问智能体",
                () -> startActivity(settingsIntent(ctx, "Web"))));
        feature.addView(row(ctx, "系统诊断", "会话 / 工具 / 运行时快照",
                () -> startActivity(new Intent(ctx, DiagnosticsActivity.class))));
        feature.addView(row(ctx, "关于 · 版本", AboutDialog.versionOf(ctx),
                () -> AboutDialog.show(ctx)));

        // 文件管理
        tools.addView(row(ctx, "文件管理", "工作区浏览 · 编辑 · 压缩",
                () -> startActivity(new Intent(ctx, FeatureHostActivity.class)
                        .putExtra(FeatureHostActivity.EXTRA_FRAGMENT, "files")
                        .putExtra(FeatureHostActivity.EXTRA_TITLE, "文件管理"))));
        return v;
    }

    private Intent settingsIntent(Context ctx, String title) {
        return new Intent(ctx, SettingsActivity.class).putExtra("SETTINGS_TITLE", title);
    }

    // 纯文字行：标题 + 副标题 + 右箭头，无图标（之前彩色圆底小 shape 全糊）
    private View row(Context ctx, String title, String subtitle, Runnable onClick) {
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16 * d, 14 * d, 16 * d, 14 * d);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.color.transparent);
        row.setForeground(ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background));
        row.setOnClickListener(v -> onClick.run());

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mid.setLayoutParams(mlp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface));
        mid.addView(t);

        TextView st = new TextView(ctx);
        st.setText(subtitle);
        st.setTextSize(12);
        st.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
        st.setPadding(0, 2 * d, 0, 0);
        mid.addView(st);

        row.addView(mid);

        // 右箭头
        TextView arrow = new TextView(ctx);
        arrow.setText("→");
        arrow.setTextSize(16);
        arrow.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
        arrow.setAlpha(0.5f);
        row.addView(arrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return row;
    }
}
