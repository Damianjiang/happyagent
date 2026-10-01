package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Config;

// 运行配置页：供应商、模型、温度、token + 各家 API Key / Base URL
public class ConfigFragment extends Fragment {

    private EditText modelBox, agentBox, maxTokensBox;
    private EditText openaiKeyBox, openaiUrlBox, googleKeyBox, anthropicKeyBox;
    private RadioButton providerOpenai, providerGoogle, providerAnthropic;
    private View openaiGroup, googleGroup, anthropicGroup;
    private SeekBar tempBar;
    private TextView tempLabel;

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
        providerOpenai = v.findViewById(R.id.config_provider_openai);
        providerGoogle = v.findViewById(R.id.config_provider_google);
        providerAnthropic = v.findViewById(R.id.config_provider_anthropic);
        openaiGroup = v.findViewById(R.id.config_provider_openai_group);
        googleGroup = v.findViewById(R.id.config_provider_google_group);
        anthropicGroup = v.findViewById(R.id.config_provider_anthropic_group);
        tempBar = v.findViewById(R.id.config_temp);
        tempLabel = v.findViewById(R.id.config_temp_label);
        Button saveBtn = v.findViewById(R.id.config_save);

        final Config c = AgentBackend.get().getConfig();
        modelBox.setText(c.model);
        agentBox.setText(c.agentName);
        maxTokensBox.setText(String.valueOf(c.maxTokens));
        openaiKeyBox.setText(c.openaiKey);
        openaiUrlBox.setText(c.openaiBaseUrl);
        googleKeyBox.setText(c.googleKey);
        anthropicKeyBox.setText(c.anthropicKey);

        tempBar.setMax(2000);
        tempBar.setProgress(c.temperature);
        tempLabel.setText("温度: " + (c.temperature / 100.0));
        tempBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tempLabel.setText("温度: " + (progress / 100.0));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 三家 Radio 单选，点了就显对应凭据分组、同步模型默认值 + 刷新模型快捷列表
        providerOpenai.setOnClickListener(vv -> onProviderPicked(v));
        providerGoogle.setOnClickListener(vv -> onProviderPicked(v));
        providerAnthropic.setOnClickListener(vv -> onProviderPicked(v));
        checkProviderRadio(c.getProvider(), v);

        saveBtn.setOnClickListener(vv -> {
            // workspace/autoCommit 没有可编辑 UI（引擎用固定沙箱根、App 无 git），
            // 保存时沿用已加载配置的原值，不做会误导人的假编辑器，也不丢老数据
            Config nc = new Config(
                    agentBox.getText().toString().trim(),
                    modelBox.getText().toString().trim(),
                    tempBar.getProgress(),
                    parseIntSafe(maxTokensBox.getText().toString(), 4096),
                    c.autoCommit,
                    c.workspace,
                    currentProvider(),
                    openaiKeyBox.getText().toString().trim(),
                    openaiUrlBox.getText().toString().trim(),
                    googleKeyBox.getText().toString().trim(),
                    anthropicKeyBox.getText().toString().trim());
            AgentBackend.get().updateConfig(nc);
            Toast.makeText(getContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        });
        return v;
    }

    private void onProviderPicked(View v) {
        String p = currentProvider();
        openaiGroup.setVisibility(Config.PROVIDER_OPENAI.equals(p) ? View.VISIBLE : View.GONE);
        googleGroup.setVisibility(Config.PROVIDER_GOOGLE.equals(p) ? View.VISIBLE : View.GONE);
        anthropicGroup.setVisibility(Config.PROVIDER_ANTHROPIC.equals(p) ? View.VISIBLE : View.GONE);
        // 模型框还停在上家默认值时，换成本家默认，避免拿别家的模型 id 调接口
        String cur = modelBox.getText().toString().trim();
        String[] providers = {Config.PROVIDER_OPENAI, Config.PROVIDER_GOOGLE, Config.PROVIDER_ANTHROPIC};
        for (String q : providers) {
            if (!q.equals(p) && defaultModelFor(q).equals(cur)) {
                modelBox.setText(defaultModelFor(p));
                break;
            }
        }
        fillModelChips(v, p);
    }

    // 按供应商铺一排常用模型 chip，点一下填入模型框；模型框仍支持手输自定义
    private void fillModelChips(View root, String provider) {
        LinearLayout chips = root.findViewById(R.id.config_model_chips);
        chips.removeAllViews();
        String[] models = modelsFor(provider);
        for (String m : models) {
            TextView chip = new TextView(root.getContext());
            chip.setText(m);
            chip.setTextSize(13);
            int pad = (int) (14 * root.getContext().getResources().getDisplayMetrics().density);
            int gap = (int) (6 * root.getContext().getResources().getDisplayMetrics().density);
            chip.setPadding(pad, gap, pad, gap);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(root.getContext().getResources().getColor(R.color.on_surface_variant));
            chip.setOnClickListener(vv -> modelBox.setText(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            chip.setLayoutParams(lp);
            chips.addView(chip);
        }
    }

    private String[] modelsFor(String provider) {
        if (Config.PROVIDER_GOOGLE.equals(provider)) {
            return new String[]{"gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro"};
        }
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) {
            return new String[]{"claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-1"};
        }
        return new String[]{"gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o4-mini"};
    }

    private String defaultModelFor(String provider) {
        if (Config.PROVIDER_GOOGLE.equals(provider)) return "gemini-2.5-flash";
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) return "claude-haiku-4-5";
        return "gpt-4o-mini";
    }

    private void checkProviderRadio(String provider, View v) {
        providerOpenai.setChecked(Config.PROVIDER_OPENAI.equals(provider));
        providerGoogle.setChecked(Config.PROVIDER_GOOGLE.equals(provider));
        providerAnthropic.setChecked(Config.PROVIDER_ANTHROPIC.equals(provider));
        onProviderPicked(v);
    }

    private String currentProvider() {
        if (providerGoogle.isChecked()) return Config.PROVIDER_GOOGLE;
        if (providerAnthropic.isChecked()) return Config.PROVIDER_ANTHROPIC;
        return Config.PROVIDER_OPENAI;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }
}
