package com.agenz.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.agenz.mobile.R;
import com.agenz.mobile.data.AgentBackend;
import com.agenz.mobile.model.Models.Session;
import com.agenz.mobile.model.Models.Tool;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 系统诊断页：会话数、启用/停用工具数、当前模型、最近更新时间，一眼看系统状态
public class ReportsFragment extends Fragment {

    private TextView sessionsCount, toolsEnabled, toolsDisabled;
    private TextView modelLabel, lastUpdate;
    private ProgressBar progress;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_reports, container, false);
        sessionsCount = v.findViewById(R.id.reports_sessions);
        toolsEnabled = v.findViewById(R.id.reports_tools_enabled);
        toolsDisabled = v.findViewById(R.id.reports_tools_disabled);
        modelLabel = v.findViewById(R.id.reports_model);
        lastUpdate = v.findViewById(R.id.reports_last_update);
        progress = v.findViewById(R.id.reports_progress);
        load();
        return v;
    }

    void load() {
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                AgentBackend backend = AgentBackend.get();
                final List<Session> sessions = backend.getSessions();
                final List<Tool> tools = backend.getTools();
                int enabled = 0, disabled = 0;
                for (Tool t : tools) {
                    if (t.enabled) enabled++;
                    else disabled++;
                }
                final int e = enabled, d = disabled;
                final String model = backend.getConfig().model;
                final String lastUpdated = sessions.isEmpty() ? "无" :
                        new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(new Date(sessions.get(0).updatedAt));
                if (!isAdded()) return;
                requireActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        progress.setVisibility(View.GONE);
                        sessionsCount.setText(String.valueOf(sessions.size()));
                        toolsEnabled.setText(String.valueOf(e));
                        toolsDisabled.setText(String.valueOf(d));
                        modelLabel.setText(model);
                        lastUpdate.setText(lastUpdated);
                    }
                });
            }
        }).start();
    }
}
