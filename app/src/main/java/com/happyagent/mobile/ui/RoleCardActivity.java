package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.button.MaterialButton;

// 角色卡独立页：角色名 + 角色设定 + 预设风格 + AI 生成 + 保存/清空。
// 保存即写 Prefs，每次任务前 AgentBackend 读取注入系统提示词（改了立即生效）。
public class RoleCardActivity extends SectionSettingsActivity {

    private EditText nameBox;
    private EditText cardBox;
    private MaterialButton aiBtn;

    @Override
    protected String title() {
        return "角色卡";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_rolecard;
    }

    @Override
    protected void bind() {
        nameBox = findViewById(R.id.set_role_name);
        cardBox = findViewById(R.id.set_role_card);
        nameBox.setText(prefs.getString(Prefs.KEY_ROLE_NAME, ""));
        cardBox.setText(prefs.getString(Prefs.KEY_ROLE_CARD, ""));

        fillRolePresets(findViewById(R.id.set_role_presets));

        findViewById(R.id.set_role_save).setOnClickListener(v -> {
            prefs.putString(Prefs.KEY_ROLE_CARD, cardBox.getText().toString().trim());
            prefs.putString(Prefs.KEY_ROLE_NAME, nameBox.getText().toString().trim());
            boolean any = !cardBox.getText().toString().trim().isEmpty()
                    || !nameBox.getText().toString().trim().isEmpty();
            Toast.makeText(this, any ? "角色卡已保存" : "已清除角色卡", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.set_role_clear).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("清空角色卡？")
                        .setMessage("恢复默认助手人格，角色名与设定都会被清空。")
                        .setPositiveButton("清空", (d, w) -> {
                            nameBox.setText("");
                            cardBox.setText("");
                            prefs.putString(Prefs.KEY_ROLE_NAME, "");
                            prefs.putString(Prefs.KEY_ROLE_CARD, "");
                            Toast.makeText(this, "已恢复默认人格", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        aiBtn = findViewById(R.id.set_role_ai);
        aiBtn.setOnClickListener(v -> showAiGenerateDialog());
    }

    // AI 生成：填一句人设方向 → 后台调 LLM → 回填设定框（可改后保存）
    private void showAiGenerateDialog() {
        final EditText q = new EditText(this);
        q.setHint("想生成什么角色？如：严谨的代码审查助手 / 耐心的编程老师…");
        q.setPadding(48, 24, 48, 8);
        new AlertDialog.Builder(this)
                .setTitle("AI 生成角色卡")
                .setMessage("填一句你想生成的人设方向，AI 会写成可直接用的角色设定（需已配置模型 Key）。")
                .setView(q)
                .setPositiveButton("生成", (d, w) -> {
                    final String want = q.getText().toString().trim();
                    if (want.isEmpty()) {
                        Toast.makeText(this, "先写一句想要的人设方向", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    aiBtn.setEnabled(false);
                    aiBtn.setText("生成中…");
                    new Thread(() -> {
                        String out = AgentBackend.get().llmGenerate(
                                "请为端侧 Android 助手写一段角色设定（系统提示词），要求：围绕「"
                                        + want + "」，包含称呼、性格、说话风格、行为边界。"
                                        + "直接输出设定正文，不要解释，120~240 字。");
                        runOnUiThread(() -> {
                            aiBtn.setEnabled(true);
                            aiBtn.setText("AI 生成角色卡");
                            if (out == null || out.startsWith("生成失败") || out.contains("没有配置 API Key")) {
                                Toast.makeText(this, out == null ? "生成失败" : out, Toast.LENGTH_LONG).show();
                            } else {
                                cardBox.setText(out.trim());
                                Toast.makeText(this, "已生成并填入角色设定（可再改后保存）", Toast.LENGTH_LONG).show();
                            }
                        });
                    }, "role-ai").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 预设风格 chips：点一下把「角色名 + 角色设定」一起填进输入框
    private void fillRolePresets(LinearLayout row) {
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        String[][] presets = {
            {"小助", "说话简短直接，先结论后细节；技术话题给可运行代码；默认中文。"},
            {"严谨工手", "先结论后推理；不确定就明说、不臆造；代码必须可运行且带边界处理。"},
            {"阿教", "像耐心的老师，一步步讲原理，给例子与类比；先讲清再动手。"},
            {"脑洞", "头脑风暴模式，给多个方案并说取舍；语言轻松但关键处准确。"},
            {"快答", "尽可能简短、要点式回答；不废话，除非要求展开。"},
        };
        for (String[] p : presets) {
            TextView chip = new TextView(this);
            chip.setText(p[0]);
            chip.setTextSize(13);
            int pad = 14 * d;
            int gap = 6 * d;
            chip.setPadding(pad, gap, pad, gap);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant));
            chip.setOnClickListener(vv -> {
                nameBox.setText(p[0]);
                cardBox.setText(p[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
    }
}
