package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Config;

// 运行配置页：供应商、模型、温度、token、工作区 + 各家 API Key / Base URL
public class ConfigFragment extends Fragment {

    private EditText modelBox, agentBox, workspaceBox, maxTokensBox;
    private EditText openaiKeyBox, openaiUrlBox, googleKeyBox, anthropicKeyBox;
    private RadioButton providerOpenai, providerGoogle, providerAnthropic;
    private View openaiGroup, googleGroup, anthropicGroup;
    private SeekBar tempBar;
    private TextView tempLabel;
    private com.google.android.material.switchmaterial.SwitchMaterial autoCommit;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        modelBox = v.findViewById(R.id.config_model);
        agentBox = v.findViewById(R.id.config_agent);
        workspaceBox = v.findViewById(R.id.config_workspace);
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
        autoCommit = v.findViewById(R.id.config_autocommit);
        Button saveBtn = v.findViewById(R.id.config_save);

        final Config c = AgentBackend.get().getConfig();
        modelBox.setText(c.model);
        agentBox.setText(c.agentName);
        workspaceBox.setText(c.workspace);
        maxTokensBox.setText(String.valueOf(c.maxTokens));
        openaiKeyBox.setText(c.openaiKey);
        openaiUrlBox.setText(c.openaiBaseUrl);
        googleKeyBox.setText(c.googleKey);
        anthropicKeyBox.setText(c.anthropicKey);
        autoCommit.setChecked(c.autoCommit);

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

        // 三家 Radio 单选，点了就显对应凭据分组、同步模型默认值
        providerOpenai.setOnClickListener(vv -> onProviderPicked());
        providerGoogle.setOnClickListener(vv -> onProviderPicked());
        providerAnthropic.setOnClickListener(vv -> onProviderPicked());
        checkProviderRadio(c.getProvider());

        saveBtn.setOnClickListener(vv -> {
            Config nc = new Config(
                    agentBox.getText().toString().trim(),
                    modelBox.getText().toString().trim(),
                    tempBar.getProgress(),
                    parseIntSafe(maxTokensBox.getText().toString(), 4096),
                    autoCommit.isChecked(),
                    workspaceBox.getText().toString().trim(),
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

    private void onProviderPicked() {
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
    }

    private String defaultModelFor(String provider) {
        if (Config.PROVIDER_GOOGLE.equals(provider)) return "gemini-2.5-flash";
        if (Config.PROVIDER_ANTHROPIC.equals(provider)) return "claude-haiku-4-5";
        return "gpt-4o-mini";
    }

    private void checkProviderRadio(String provider) {
        providerOpenai.setChecked(Config.PROVIDER_OPENAI.equals(provider));
        providerGoogle.setChecked(Config.PROVIDER_GOOGLE.equals(provider));
        providerAnthropic.setChecked(Config.PROVIDER_ANTHROPIC.equals(provider));
        onProviderPicked();
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
