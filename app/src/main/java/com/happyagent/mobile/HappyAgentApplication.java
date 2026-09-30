package com.happyagent.mobile;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;

// 启动入口：先装崩溃兜底，再套主题，最后后台把 agent 存档读出来
public class HappyAgentApplication extends Application {

    private static HappyAgentApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        CrashHandler.install(this);

        Prefs prefs = new Prefs(this);
        AppCompatDelegate.setDefaultNightMode(
                prefs.getInt(Prefs.KEY_NIGHT_MODE, Prefs.NIGHT_FOLLOW_SYSTEM));

        // 后台读存档，界面启动不被 IO 拖住
        AgentBackend.get().preload();
    }

    public static HappyAgentApplication get() {
        return instance;
    }
}
