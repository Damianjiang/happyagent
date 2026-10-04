package com.happyagent.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.bottomnavigation.BottomNavigationView;

// 主界面：顶部应用栏 + 底部四个功能页，Fragment 首次点开才建，之后切来切去很轻
public class MainActivity extends AppCompatActivity {

    // 外部 intent 指定先显示哪个 tab（诊断页"能改的行"点一下跳去配置页等）
    public static final String EXTRA_GOTO_TAB = "goto_tab";

    private BottomNavigationView nav;
    private ShakeLog shakeLog;
    private SessionsFragment sessionsFragment;
    private SettingsListFragment settingsListFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_main);

        nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            openTab(item.getItemId());
            return true;
        });

        // 首次打开：先把主界面铺好（欢迎页盖在上面，结束后露出），再亮出欢迎页做开源免费警示
        Prefs prefs = new Prefs(this);
        boolean firstLaunch = !prefs.getBoolean(Prefs.KEY_FIRST_LAUNCH, false);

        if (savedInstanceState == null || firstLaunch) {
            // 底部只剩「首页 / 设置」，默认落在首页（会话）
            nav.setSelectedItemId(R.id.nav_home);
            openTab(R.id.nav_home);
        }

        // 外部指定先显示某 tab（诊断页"能改的行"点一下跳去某 tab）
        int gotoTab = getIntent().getIntExtra(EXTRA_GOTO_TAB, -1);
        if (gotoTab != -1) {
            nav.setSelectedItemId(gotoTab);
            openTab(gotoTab);
        }

        if (firstLaunch) {
            startActivity(new Intent(this, WelcomeActivity.class));
        }

        // 摇一摇记日志：开关开着才挂传感器；离开界面即摘掉，省电也避免后台误触发
        shakeLog = new ShakeLog(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int gotoTab = intent == null ? -1 : intent.getIntExtra(EXTRA_GOTO_TAB, -1);
        if (gotoTab != -1) {
            nav.setSelectedItemId(gotoTab);
            openTab(gotoTab);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shakeLog != null) shakeLog.start();
    }

    @Override
    protected void onPause() {
        if (shakeLog != null) shakeLog.stop();
        super.onPause();
    }

    private void openTab(int id) {
        Fragment target;
        if (id == R.id.nav_settings) {
            target = lazySettings();
        } else {
            target = lazySessions();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, target)
                .commit();
    }

    private Fragment lazySessions() {
        if (sessionsFragment == null) sessionsFragment = new SessionsFragment();
        return sessionsFragment;
    }

    private Fragment lazySettings() {
        if (settingsListFragment == null) settingsListFragment = new SettingsListFragment();
        return settingsListFragment;
    }

    // 右下角加号已去掉，新对话入口改到首页（SessionsFragment 顶部按钮）
}
