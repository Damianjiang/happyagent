package com.happyagent.mobile.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;

// 设置主页：仿安卓原生设置——彩色圆图标 + 标题/副标题行，分「功能设置」「工具与文件」两组。
// 功能项跳对应功能设置页；工具/文件跳 FeatureHostActivity 宿主页。
public class SettingsListFragment extends Fragment {

    // 与截图一致的彩色圆底（浅色系；暗色下仍可读，图标白色）
    private static final int C_BLUE = 0xFF1565C0;
    private static final int C_GREEN = 0xFF2E7D32;
    private static final int C_ORANGE = 0xFFE65100;
    private static final int C_TEAL = 0xFF00695C;
    private static final int C_AMBER = 0xFFF9A825;
    private static final int C_CYAN = 0xFF00838F;
    private static final int C_PURPLE = 0xFF7B1FA2;
    private static final int C_GRAY = 0xFF546E7A;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_settings_list, container, false);
        final Context ctx = requireContext();
        LinearLayout feature = v.findViewById(R.id.settings_list);
        LinearLayout tools = v.findViewById(R.id.tools_files_list);

        // 功能设置组
        feature.addView(row(ctx, R.drawable.ic_gear, C_BLUE, "模型接入",
                "供应商 · 模型 · 温度 · 最大步数 · 拉取上游模型",
                iv -> startActivity(new Intent(ctx, ModelConfigActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_nav_config, C_GREEN, "外观 · 行为",
                "主题 · 强调色 · 字号 · 震动 · 摇一摇 · 朗读",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_gear, C_ORANGE, "个性化",
                "角色卡 · 提示词编辑 · 标签 / 提示词片段 · AI 生成角色卡",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_nav_tools, C_TEAL, "容器环境",
                "免 root Alpine：一键部署 · 状态 · 终端",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_nav_tools, C_AMBER, "GUI 自动化",
                "无障碍服务开关状态",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_stat_web, C_CYAN, "Web 服务",
                "在浏览器访问这台手机的智能体",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_file, C_PURPLE, "数据管理",
                "授权目录 · 撤销授权 · 清除会话",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_nav_reports, C_GRAY, "系统诊断",
                "会话 / 工具 / 运行时 状态快照",
                iv -> startActivity(new Intent(ctx, DiagnosticsActivity.class))));
        feature.addView(row(ctx, R.drawable.ic_gear, C_BLUE, "关于",
                "版本 · 说明",
                iv -> AboutDialog.show(ctx)));

        // 版本号（动态取，点击跳 GitHub 仓库）
        String ver = AboutDialog.versionOf(ctx);
        feature.addView(row(ctx, R.drawable.ic_gear, C_CYAN, "v" + ver,
                "点击跳转 GitHub 仓库",
                iv -> {
                    android.content.Intent bi = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Damianjiang/happyagent"));
                    ctx.startActivity(bi);
                }));

        // 工具与文件组（原底部 tab，现收进设置列表）
        tools.addView(row(ctx, R.drawable.ic_nav_tools, C_TEAL, "工具 / 插件",
                "行内开关即时生效，点卡片看说明",
                iv -> startActivity(new Intent(ctx, FeatureHostActivity.class)
                        .putExtra(FeatureHostActivity.EXTRA_FRAGMENT, "tools")
                        .putExtra(FeatureHostActivity.EXTRA_TITLE, "工具 / 插件"))));
        tools.addView(row(ctx, R.drawable.ic_nav_files, C_ORANGE, "文件",
                "工作区 / 我的目录：编辑 · 预览 · 发给智能体",
                iv -> startActivity(new Intent(ctx, FeatureHostActivity.class)
                        .putExtra(FeatureHostActivity.EXTRA_FRAGMENT, "files")
                        .putExtra(FeatureHostActivity.EXTRA_TITLE, "文件"))));
        return v;
    }

    // 原生设置行：彩色圆底白图标 + 标题/副标题，整行可点
    private View row(Context ctx, int iconRes, int circleColor, String title, String subtitle,
                     View.OnClickListener onClick) {
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = 12 * d;
        row.setPadding(pad, pad, pad, pad);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.color.transparent);
        // 可点水波反馈
        row.setForeground(ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);

        // 彩色圆底 + 白色图标
        FrameLayout iconWrap = new FrameLayout(ctx);
        int csz = 40 * d;
        iconWrap.setBackground(circleDrawable(circleColor));
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Color.WHITE);
        int ipx = 24 * d;
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ipx, ipx);
        ilp.gravity = Gravity.CENTER;
        iconWrap.addView(ic, ilp);
        row.addView(iconWrap, new LinearLayout.LayoutParams(csz, csz));

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = 14 * d;
        mlp.rightMargin = 8 * d;
        mid.setLayoutParams(mlp);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface));
        mid.addView(t);
        TextView st = new TextView(ctx);
        st.setText(subtitle);
        st.setTextSize(13);
        st.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = 1 * d;
        st.setLayoutParams(stlp);
        mid.addView(st);
        row.addView(mid);

        row.setOnClickListener(onClick);
        return row;
    }

    // 纯圆彩色底（运行时画，不占 xml 资源）
    private static Drawable circleDrawable(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
