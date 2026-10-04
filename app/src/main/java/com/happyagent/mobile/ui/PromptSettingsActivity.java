package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.PromptTags;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

// 提示词 / Skill：自定义系统提示词 + 标签片段（启用段注入系统提示词）
public class PromptSettingsActivity extends SectionSettingsActivity {

    @Override
    protected String title() {
        return "提示词 / Skill";
    }

    @Override
    protected int contentRes() {
        return R.layout.content_settings_prompt;
    }

    @Override
    protected void bind() {
        bindSystemPrompt();
        bindPromptTags();
    }

    private void bindSystemPrompt() {
        EditText sysPromptBox = findViewById(R.id.set_system_prompt);
        TextView sysPromptState = findViewById(R.id.set_system_prompt_state);
        String sysInit = prefs.getString(Prefs.KEY_SYSTEM_PROMPT, "").trim();
        sysPromptBox.setText(sysInit);
        setPromptState(sysPromptState, sysInit.isEmpty());
        findViewById(R.id.set_system_prompt_save).setOnClickListener(v -> {
            String s = sysPromptBox.getText().toString().trim();
            prefs.putString(Prefs.KEY_SYSTEM_PROMPT, s);
            setPromptState(sysPromptState, s.isEmpty());
            Toast.makeText(this, s.isEmpty() ? "已恢复内置默认提示词" : "自定义提示词已保存",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void setPromptState(TextView tv, boolean empty) {
        tv.setText(empty ? "未自定义（用内置默认）" : "已自定义");
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                empty ? R.color.status_failed : R.color.status_done));
    }

    // 标签 / 提示词片段：加载已存 JSON，渲染列表+预设+新建；开关/删除/新建即存
    private void bindPromptTags() {
        LinearLayout list = findViewById(R.id.set_tag_list);
        TextView empty = findViewById(R.id.set_tag_empty);
        LinearLayout presets = findViewById(R.id.set_tag_presets);

        java.util.List<PromptTags.Tag> tags =
                PromptTags.load(prefs.getString(Prefs.KEY_PROMPT_TAGS, ""));
        renderTagList(list, empty, tags);
        buildTagPresetChips(presets, tags);

        findViewById(R.id.set_tag_add).setOnClickListener(v -> promptNewTag(tags));
    }

    private void renderTagList(LinearLayout list, TextView empty, java.util.List<PromptTags.Tag> tags) {
        list.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        empty.setVisibility(tags.isEmpty() ? View.VISIBLE : View.GONE);
        for (final PromptTags.Tag t : tags) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = 8 * d;
            row.setPadding(0, pad, 0, pad);

            TextView name = new TextView(this);
            name.setText(t.name.isEmpty() ? "标签" : t.name);
            name.setTextSize(14);
            name.setTextColor(getThemeColor(R.color.on_surface));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nlp);

            final SwitchMaterial sw = new SwitchMaterial(this);
            sw.setChecked(t.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                t.enabled = on;
                saveTags(tags);
                Toast.makeText(this, "标签「" + (t.name.isEmpty() ? "未命名" : t.name)
                        + "」" + (on ? "已启用" : "已停用"), Toast.LENGTH_SHORT).show();
            });
            row.addView(sw);

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(getThemeColor(R.color.status_failed));
            del.setPadding(16 * d, 0, 0, 0);
            del.setOnClickListener(vv -> {
                tags.remove(t);
                saveTags(tags);
                renderTagList(list, empty, tags);
                buildTagPresetChips(findViewById(R.id.set_tag_presets), tags);
            });
            row.addView(del);

            list.addView(row);
        }
    }

    private void buildTagPresetChips(LinearLayout row, java.util.List<PromptTags.Tag> tags) {
        row.removeAllViews();
        int d = (int) getResources().getDisplayMetrics().density;
        java.util.List<PromptTags.Tag> presets = PromptTags.presets();
        for (final PromptTags.Tag p : presets) {
            boolean exists = false;
            for (PromptTags.Tag t : tags) if (p.name.equals(t.name)) exists = true;
            if (exists) continue;
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setTextSize(13);
            int pad = 14 * d;
            int gap = 6 * d;
            chip.setPadding(pad, gap, pad, gap);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(getThemeColor(R.color.on_surface_variant));
            chip.setOnClickListener(vv -> {
                tags.add(new PromptTags.Tag(p.name, p.content, true));
                saveTags(tags);
                renderTagList(findViewById(R.id.set_tag_list), findViewById(R.id.set_tag_empty), tags);
                buildTagPresetChips(row, tags);
                Toast.makeText(this, "已加入标签「" + p.name + "」（默认启用）", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            row.addView(chip, lp);
        }
        if (row.getChildCount() == 0) {
            TextView note = new TextView(this);
            note.setText("预设已全部加入");
            note.setTextSize(12);
            note.setTextColor(getThemeColor(R.color.on_surface_variant));
            row.addView(note);
        }
    }

    private void promptNewTag(final java.util.List<PromptTags.Tag> tags) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int d = (int) getResources().getDisplayMetrics().density;
        box.setPadding(48 * d, 8, 48 * d, 0);
        final EditText nm = new EditText(this);
        nm.setHint("标签名（给自己看）");
        final EditText ct = new EditText(this);
        ct.setHint("提示词内容（发给 AI）");
        ct.setLines(3);
        box.addView(nm);
        box.addView(ct, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("新建标签")
                .setView(box)
                .setPositiveButton("保存", (dlg, w) -> {
                    String n = nm.getText().toString().trim();
                    String c = ct.getText().toString().trim();
                    if (c.isEmpty()) {
                        Toast.makeText(this, "提示词内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tags.add(new PromptTags.Tag(n, c, true));
                    saveTags(tags);
                    renderTagList(findViewById(R.id.set_tag_list), findViewById(R.id.set_tag_empty), tags);
                    buildTagPresetChips(findViewById(R.id.set_tag_presets), tags);
                    Toast.makeText(this, "已新建标签" + (n.isEmpty() ? "" : "「" + n + "」"),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveTags(java.util.List<PromptTags.Tag> tags) {
        prefs.putString(Prefs.KEY_PROMPT_TAGS, PromptTags.save(tags));
    }
}
