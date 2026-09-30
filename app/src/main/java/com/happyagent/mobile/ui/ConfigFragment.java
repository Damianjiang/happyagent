package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Config;

// 运行配置页：改模型、温度、token、工作区这些，保存后下次任务直接生效
public class ConfigFragment extends Fragment {

    private EditText modelBox, agentBox, workspaceBox, maxTokensBox;
    private SeekBar tempBar;
    private TextView tempLabel;
    private com.google.android.material.switchmaterial.SwitchMaterial autoCommit;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        modelBox = v.findViewById(R.id.config_model);
        agentBox = v.findViewById(R.id.config_agent);
        workspaceBox = v.findViewById(R.id.config_workspace);
        tempBar = v.findViewById(R.id.config_temp);
        tempLabel = v.findViewById(R.id.config_temp_label);
        maxTokensBox = v.findViewById(R.id.config_max_tokens);
        autoCommit = v.findViewById(R.id.config_autocommit);
        Button saveBtn = v.findViewById(R.id.config_save);

        final Config c = AgentBackend.get().getConfig();
        modelBox.setText(c.model);
        agentBox.setText(c.agentName);
        workspaceBox.setText(c.workspace);
        maxTokensBox.setText(String.valueOf(c.maxTokens));
        autoCommit.setChecked(c.autoCommit);

        tempBar.setMax(2000);
        tempBar.setProgress(c.temperature);
        tempLabel.setText("温度: " + (c.temperature / 100.0));
        tempBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tempLabel.setText("温度: " + (progress / 100.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Config nc = new Config(
                        agentBox.getText().toString().trim(),
                        modelBox.getText().toString().trim(),
                        tempBar.getProgress(),
                        parseIntSafe(maxTokensBox.getText().toString(), 4096),
                        autoCommit.isChecked(),
                        workspaceBox.getText().toString().trim());
                AgentBackend.get().updateConfig(nc);
                Toast.makeText(getContext(), "配置已保存", Toast.LENGTH_SHORT).show();
            }
        });
        return v;
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
