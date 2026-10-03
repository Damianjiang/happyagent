package com.happyagent.mobile.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;

// 设置主页：一张"功能设置"列表，点一项跳进对应功能设置页。
// 模型接入/系统诊断做独立页；外观·行为/个性化/容器/GUI/Web/数据/关于 → SettingsActivity；容器终端 → TerminalActivity。
public class SettingsListFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_settings_list, container, false);
        LinearLayout list = v.findViewById(R.id.settings_list);
        final Context ctx = requireContext();

        list.addView(makeRow(ctx, R.drawable.ic_gear, "模型接入",
                "供应商 · 模型 · 温度 · 最大步数 · 拉取上游模型",
                iv -> startActivity(new Intent(ctx, ModelConfigActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_nav_reports, "系统诊断",
                "会话 / 工具 / 运行时 / 模型接入 状态快照",
                iv -> startActivity(new Intent(ctx, DiagnosticsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_nav_config, "外观 · 行为",
                "主题 · 强调色 · 字号 · 震动 · 摇一摇 · 起始页 · 朗读",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_gear, "个性化",
                "角色卡 · 提示词编辑 · 标签 / 提示词片段 · AI 生成角色卡",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_nav_tools, "容器环境",
                "免 root Alpine：一键部署 · 状态",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_gear, "容器终端",
                "部署后手动在 Alpine 里跑命令",
                iv -> startActivity(new Intent(ctx, TerminalActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_nav_tools, "GUI 自动化",
                "无障碍服务开关状态",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_stat_web, "Web 服务",
                "在浏览器访问这台手机的智能体",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_file, "数据管理",
                "授权目录 · 撤销授权 · 清除会话",
                iv -> startActivity(new Intent(ctx, SettingsActivity.class))));
        list.addView(makeRow(ctx, R.drawable.ic_gear, "关于",
                "版本 · 说明",
                iv -> AboutDialog.show(ctx)));
        return v;
    }

    // 一行：左图标 + 中标题/副标题 + 右 chevron，整行可点，发丝线卡片
    private View makeRow(Context ctx, int iconRes, String title, String subtitle,
                         View.OnClickListener onClick) {
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = 14 * d;
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(roundedCard(ctx));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = 8 * d;
        row.setLayoutParams(rlp);

        int icPx = 24 * d;
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setContentDescription(title);
        row.addView(ic, new LinearLayout.LayoutParams(icPx, icPx));

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = 12 * d;
        mlp.rightMargin = 8 * d;
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
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = 2 * d;
        st.setLayoutParams(stlp);
        mid.addView(st);
        row.addView(mid);

        int cpx = 18 * d;
        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setContentDescription("进入");
        row.addView(chevron, new LinearLayout.LayoutParams(cpx, cpx));

        row.setOnClickListener(onClick);
        return row;
    }

    private GradientDrawable roundedCard(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(ctx.getResources().getDisplayMetrics().density * 14);
        g.setColor(ContextCompat.getColor(ctx, R.color.surface));
        g.setStroke((int) ctx.getResources().getDisplayMetrics().density,
                ContextCompat.getColor(ctx, R.color.hairline));
        return g;
    }
}
