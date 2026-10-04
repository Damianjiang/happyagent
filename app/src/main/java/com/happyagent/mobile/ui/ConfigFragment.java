package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.ModelCatalog;
import com.happyagent.mobile.model.Models.Config;

// 模型接入页：供应商分段单选 + 各家 Key/BaseURL + 模型（列表点选 / 拉取上游）+ 步数，
// 保存按钮固定整页最底部；温度不开放（固定走默认值）。模型列表带上下文上限自动识别。
public class ConfigFragment extends Fragment {

    private EditText modelBox, agentBox, maxTokensBox;
    private EditText openaiKeyBox, openaiUrlBox, googleKeyBox, anthropicKeyBox;
    private Button providerOpenai, providerGoogle, providerAnthropic;
    private View openaiGroup, googleGroup, anthropicGroup;
    private EditText maxStepsBox;
    private TextView ctxHint;
    // 供应商当前选中（分段按钮自管互斥）
    private String selProvider = Config.PROVIDER_OPENAI;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        modelBox = v.findViewById(R.id.config_model);
        agentBox = v.findViewById(R.id.config_agent);
        maxTokensBox = v.findViewById(R.id.config_max_tokens);
        openaiKeyBox = v.findViewById(R.id.config_openai_key);
        openaiUrlBox = v.findViewById(R.id.config_openai_url);
        googleKeyBox = v.findViewById(R.id.config_google_key);
        anthropicKeyBox = v.findViewById(R.id.config_anthropic_key);
        maxStepsBox = v.findViewById(R.id.config_max_steps);
        providerOpenai = v.findViewById(R.id.config_provider_openai);
        providerGoogle = v.findViewById(R.id.config_provider_google);
        providerAnthropic = v.findViewById(R.id.config_provider_anthropic);
        openaiGroup = v.findViewById(R.id.config_provider_openai_group);
        googleGroup = v.findViewById(R.id.config_provider_google_group);
        anthropicGroup = v.findViewById(R.id.config_provider_anthropic_group);
        ctxHint = v.findViewById(R.id.config_ctx_hint);
        Button saveBtn = v.findViewById(R.id.config_save);

        final Config c = AgentBackend.get().getConfig();
        modelBox.setText(c.model);
        agentBox.setText(c.agentName);
        maxTokensBox.setText(String.valueOf(c.maxTokens));
        maxStepsBox.setText(String.valueOf(c.normalizedMaxSteps()));
        openaiKeyBox.setText(c.openaiKey);
        openaiUrlBox.setText(c.openaiBaseUrl);
        googleKeyBox.setText(c.googleKey);
        anthropicKeyBox.setText(c.anthropicKey);
        updateCtxHint();

        // 模型名一变，上下文上限提示跟着变（自动识别）
        modelBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int cc) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int cc) { updateCtxHint(); }
            @Override public void afterTextChanged(Editable x) {}
        });

        // 模型列表：预设 + 已拉取合成，带搜索与上下文标注；点选直接填入
        v.findViewById(R.id.config_pick_model).setOnClickListener(vv ->
                ModelListDialog.show(requireContext(),
                        modelBox.getText().toString().trim(),
                        picked -> modelBox.setText(picked)));

        // 拉取上游真实模型：成功即持久化并弹出列表（不再是一排挤扁的小 chip）
        final Button fetchBtn = v.findViewById(R.id.config_fetch_models);
        final TextView modelsHint = v.findViewById(R.id.config_models_hint);
        fetchBtn.setOnClickListener(vv -> {
            final String provider = currentProvider();
            String base;
            String key;
            if (Config.PROVIDER_GOOGLE.equals(provider)) {
                base = "https://generativelanguage.googleapis.com";
                key = googleKeyBox.getText().toString().trim();
            } else if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
                base = "https://api.anthropic.com";
                key = anthropicKeyBox.getText().toString().trim();
            } else {
                base = openaiUrlBox.getText().toString().trim();
                if (base.isEmpty()) base = "https://api.openai.com/v1";
                key = openaiKeyBox.getText().toString().trim();
            }
            fetchBtn.setEnabled(false);
            fetchBtn.setText("拉取中…");
            modelsHint.setVisibility(View.VISIBLE);
            modelsHint.setText("正在从供应商拉取模型列表…");
            ModelCatalog.fetch(requireContext(), provider, base, key,
                    new ModelCatalog.Callback() {
                        @Override
                        public void onResult(boolean ok, String errMsg, java.util.List<String> models) {
                            fetchBtn.setEnabled(true);
                            fetchBtn.setText("拉取上游模型");
                            if (ok && models != null && !models.isEmpty()) {
                                ModelCatalog.saveFetched(requireContext(), provider, models);
                                modelsHint.setVisibility(View.GONE);
                                // 拉完直接弹列表，点一下就切模型
                                ModelListDialog.show(requireContext(),
                                        modelBox.getText().toString().trim(),
                                        picked -> modelBox.setText(picked));
                            } else {
                                modelsHint.setText(ok ? "上游暂无可用模型" : "拉取失败：" + errMsg);
                            }
                        }
                    });
        });

        // 三家分段按钮互斥单选
        providerOpenai.setOnClickListener(vv -> pickProvider(Config.PROVIDER_OPENAI));
        providerGoogle.setOnClickListener(vv -> pickProvider(Config.PROVIDER_GOOGLE));
        providerAnthropic.setOnClickListener(vv -> pickProvider(Config.PROVIDER_ANTHROPIC));
        pickProvider(c.getProvider());

        // 保存固定在页面最底部，改完所有项一点全存；温度走默认值不再开放
        saveBtn.setOnClickListener(vv -> {
            Config nc = new Config(
                    agentBox.getText().toString().trim(),
                    modelBox.getText().toString().trim(),
                    70,   // 温度固定默认 0.7，引擎也按固定值发，不再开放调节
                    parseIntSafe(maxTokensBox.getText().toString(), 4096),
                    c.autoCommit,
                    c.workspace,
                    currentProvider(),
                    openaiKeyBox.getText().toString().trim(),
                    openaiUrlBox.getText().toString().trim(),
                    googleKeyBox.getText().toString().trim(),
                    anthropicKeyBox.getText().toString().trim());
            nc.maxSteps = clampSteps(maxStepsBox.getText().toString());
            AgentBackend.get().updateConfig(nc);
            Toast.makeText(getContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        });
        return v;
    }

    // 上下文上限提示：随模型名实时更新
    private void updateCtxHint() {
        if (ctxHint == null || modelBox == null) return;
        String m = modelBox.getText().toString().trim();
        if (m.isEmpty()) {
            ctxHint.setText("上下文上限：—（选中模型后自动识别）");
        } else {
            ctxHint.setText("上下文上限：" + ModelCatalog.contextLabel(m)
                    + "（按模型自动识别）");
        }
    }

    // 分段按钮互斥：点一家 → 记 selProvider + 显对应凭据分组 + 选中态样式 + 同步模型默认值
    private void pickProvider(String p) {
        selProvider = p;
        openaiGroup.setVisibility(Config.PROVIDER_OPENAI.equals(p) ? View.VISIBLE : View.GONE);
        googleGroup.setVisibility(Config.PROVIDER_GOOGLE.equals(p) ? View.VISIBLE : View.GONE);
        anthropicGroup.setVisibility(Config.PROVIDER_ANTHROPIC.equals(p) ? View.VISIBLE : View.GONE);
        styleSeg(providerOpenai, Config.PROVIDER_OPENAI.equals(p));
        styleSeg(providerGoogle, Config.PROVIDER_GOOGLE.equals(p));
        styleSeg(providerAnthropic, Config.PROVIDER_ANTHROPIC.equals(p));
        // 模型框还停在上家默认值时，换成本家默认，避免拿别家的模型 id 调接口
        String cur = modelBox.getText().toString().trim();
        String[] providers = {Config.PROVIDER_OPENAI, Config.PROVIDER_GOOGLE, Config.PROVIDER_ANTHROPIC};
        for (String q : providers) {
            if (!q.equals(p) && defaultModelFor(q).equals(cur)) {
                modelBox.setText(defaultModelFor(p));
                break;
            }
        }
    }

    // 分段按钮选中态：选中=强调色容器底，未选=透明底+发丝描边
    private void styleSeg(Button b, boolean on) {
        b.setBackgroundResource(on ? R.drawable.bg_seg_on : R.drawable.bg_seg_off);
        b.setTextColor(on
                ? androidx.core.content.ContextCompat.getColor(requireContext(), R.color.acc_default_c_on)
                : androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_surface_variant));
    }

    private String defaultModelFor(String provider) {
        if (Config.PROVIDER_GOOGLE.equals(provider)) return "gemini-2.5-flash";
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) return "claude-haiku-4-5";
        return "gpt-4o-mini";
    }

    private String currentProvider() {
        return selProvider;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    // 最大步数收口：空/非数字/越界都回默认 10，合法值夹在 4~20
    private int clampSteps(String s) {
        int n;
        try { n = Integer.parseInt(s.trim()); }
        catch (Exception e) { return 10; }
        if (n < 4) return 4;
        if (n > 20) return 20;
        return n;
    }
}
