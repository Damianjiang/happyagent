package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;
import com.happyagent.mobile.model.Models.Config;
import com.happyagent.mobile.model.Models.Session;
import com.happyagent.mobile.model.Models.Tool;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 系统诊断页：会话/工具数、运行时（模型/温度/max token/智能体名/运行中任务）、
// 模型接入（三家 Key 各自状态 + 接口）、个性化（主题/强调色/字号）。一眼看系统状态。
public class ReportsFragment extends Fragment {

    private TextView sessionsCount, toolsEnabled, toolsDisabled;
    private TextView modelLabel, lastUpdate, baseUrlLabel, providerLabel;
    private TextView temperatureLabel, maxTokensLabel, agentNameLabel, runningLabel;
    private TextView keyOpenai, keyGoogle, keyAnthropic;
    private TextView themeLabel, accentLabel, chatSizeLabel;
    private TextView webStateLabel, webLanLabel;
    private TextView guiLabel, roleLabel, ttsLabel, maxStepsLabel;
    private ProgressBar progress;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_reports, container, false);
        sessionsCount = v.findViewById(R.id.reports_sessions);
        toolsEnabled = v.findViewById(R.id.reports_tools_enabled);
        toolsDisabled = v.findViewById(R.id.reports_tools_disabled);
        modelLabel = v.findViewById(R.id.reports_model);
        lastUpdate = v.findViewById(R.id.reports_last_update);
        baseUrlLabel = v.findViewById(R.id.reports_baseurl);
        providerLabel = v.findViewById(R.id.reports_provider);
        temperatureLabel = v.findViewById(R.id.reports_temperature);
        maxTokensLabel = v.findViewById(R.id.reports_max_tokens);
        agentNameLabel = v.findViewById(R.id.reports_agent_name);
        runningLabel = v.findViewById(R.id.reports_running);
        keyOpenai = v.findViewById(R.id.reports_key_openai);
        keyGoogle = v.findViewById(R.id.reports_key_google);
        keyAnthropic = v.findViewById(R.id.reports_key_anthropic);
        themeLabel = v.findViewById(R.id.reports_theme);
        accentLabel = v.findViewById(R.id.reports_accent);
        chatSizeLabel = v.findViewById(R.id.reports_chat_size);
        webStateLabel = v.findViewById(R.id.reports_web_state);
        webLanLabel = v.findViewById(R.id.reports_web_lan);
        guiLabel = v.findViewById(R.id.reports_gui);
        roleLabel = v.findViewById(R.id.reports_role);
        ttsLabel = v.findViewById(R.id.reports_tts);
        maxStepsLabel = v.findViewById(R.id.reports_max_steps);
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
                final Config c = backend.getConfig();
                final String model = c.model;
                final String provider = c.getProvider();
                final String endpoint = endpointOf(c);
                final String agentName = (c.agentName == null || c.agentName.isEmpty())
                        ? "Happy-Agent" : c.agentName;
                final String temp = String.valueOf(c.temperature / 100.0);
                final String maxTok = String.valueOf(c.maxTokens);
                final String running = backend.isTaskRunning()
                        ? (backend.isTaskPaused() ? "是（已暂停）" : "是（运行中）")
                        : "无";
                final String lastUpdated = sessions.isEmpty() ? "无" :
                        new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(new Date(sessions.get(0).updatedAt));

                // 个性化（读 Prefs，主线程读也便宜，这里为统一放在子线程）
                Prefs p = new Prefs(requireContext().getApplicationContext());
                final int themeMode = p.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_FOLLOW);
                final int accent = p.getInt(Prefs.KEY_ACCENT, Prefs.ACCENT_DEFAULT);
                final int chatSize = p.getInt(Prefs.KEY_CHAT_TEXT_SIZE, 0);

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
                        temperatureLabel.setText(temp);
                        maxTokensLabel.setText(maxTok);
                        agentNameLabel.setText(agentName);
                        runningLabel.setText(running);
                        lastUpdate.setText(lastUpdated);
                        providerLabel.setText(provider);
                        keyOpenai.setText(c.openaiKey == null || c.openaiKey.isEmpty() ? "未配置" : "已配置");
                        keyGoogle.setText(c.googleKey == null || c.googleKey.isEmpty() ? "未配置" : "已配置");
                        keyAnthropic.setText(c.anthropicKey == null || c.anthropicKey.isEmpty() ? "未配置" : "已配置");
                        baseUrlLabel.setText(endpoint);
                        themeLabel.setText(ThemeUtil.modeLabel(themeMode));
                        accentLabel.setText(ThemeUtil.accentLabels()[accent]);
                        chatSizeLabel.setText(chatSize == 2 ? "特大" : (chatSize == 1 ? "大" : "标准"));
                        // 能力状态（数据层早能查，界面补出）
                        boolean guiOn = com.happyagent.mobile.service.GuardService.isRunning();
                        guiLabel.setText(guiOn ? "已开启" : "未开启");
                        guiLabel.setTextColor(guiOn
                                ? androidx.core.content.ContextCompat.getColor(
                                    requireContext(), R.color.status_done)
                                : androidx.core.content.ContextCompat.getColor(
                                    requireContext(), R.color.on_surface_variant));
                        String roleCard = p.getString(Prefs.KEY_ROLE_CARD, "");
                        roleLabel.setText(roleCard.isEmpty() ? "未设置" : roleCard.length() + " 字");
                        boolean ttsOn = p.getBoolean(Prefs.KEY_TTS_ON, false);
                        ttsLabel.setText(ttsOn ? "已开启" : "已关闭");
                        maxStepsLabel.setText(String.valueOf(c.normalizedMaxSteps()));
                        refreshWeb();
                    }
                });
            }
        }).start();
    }

    // Web 服务状态（数据层 WebUiService 早就能查，界面补一行露出）
    private void refreshWeb() {
        boolean running = com.happyagent.mobile.service.WebUiService.isRunning();
        webStateLabel.setText(running ? "运行中" : "已停止");
        String lan = com.happyagent.mobile.service.WebUiService.lanIpStatic();
        int port = com.happyagent.mobile.service.WebUiService.port();
        webLanLabel.setText(running
                ? ((lan.isEmpty() ? "（获取中）" : lan) + ":" + port)
                : "—");
    }

    // 诊断页"接口"行按供应商显示请求去向，OpenAI 才谈 Base URL
    private String endpointOf(Config c) {
        switch (c.getProvider()) {
            case Config.PROVIDER_GOOGLE:
                return "generativelanguage.googleapis.com";
            case Config.PROVIDER_ANTHROPIC:
                return "api.anthropic.com/v1";
            default:
                return c.openaiBaseUrl;
        }
    }
}
