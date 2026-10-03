package com.happyagent.mobile;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;
import com.happyagent.mobile.data.TtsEngine;
import com.happyagent.mobile.ui.ThemeUtil;

// 启动入口：先装崩溃兜底，再套主题（三态主题+强调色），最后后台把 agent 存档读出来
public class HappyAgentApplication extends Application {

    private static HappyAgentApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        CrashHandler.install(this);

        // 套夜间模式（从三态主题键取；老版二值键自动迁移过来）
        ThemeUtil.applyGlobal();

        // 朗读引擎：尽早初始化（TTS 启动是异步的），任务完成后可自动念 AI 回复
        TtsEngine.get().init(this);

        // 后台读存档，界面启动不被 IO 拖住
        AgentBackend.get().preload();

        // 上次开着的 Web 服务随启动恢复（默认关）
        if (prefs().getBoolean(com.happyagent.mobile.data.Prefs.KEY_WEBUI_ON, false)) {
            com.happyagent.mobile.service.WebUiService.start(this);
        }
    }

    public static HappyAgentApplication get() {
        return instance;
    }

    private Prefs prefs() {
        return new Prefs(this);
    }
}
