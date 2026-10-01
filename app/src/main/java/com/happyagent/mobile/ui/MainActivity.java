package com.happyagent.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.data.Prefs;
import com.google.android.material.bottomnavigation.BottomNavigationView;

// 主界面：顶部应用栏 + 底部四个功能页，Fragment 首次点开才建，之后切来切去很轻
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView nav;
    private ShakeLog shakeLog;
    private SessionsFragment sessionsFragment;
    private ToolsFragment toolsFragment;
    private ConfigFragment configFragment;
    private ReportsFragment reportsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            openTab(item.getItemId());
            return true;
        });

        // 首次打开：把主界面先铺好（欢迎页盖在上面，结束后露出），再亮出欢迎页做开源免费警示
        Prefs prefs = new Prefs(this);
        boolean firstLaunch = !prefs.getBoolean(Prefs.KEY_FIRST_LAUNCH, false);

        if (savedInstanceState == null || firstLaunch) {
            // 按设置里的"起始页"决定先显示哪块
            int start = prefs.getInt(Prefs.KEY_START_PAGE, 0);
            int startId = (start == 1) ? R.id.nav_tools : R.id.nav_sessions;
            nav.setSelectedItemId(startId);
            openTab(startId);
        }

        if (firstLaunch) {
            startActivity(new Intent(this, WelcomeActivity.class));
        }

        // 摇一摇记日志：开关开着才挂传感器；离开界面即摘掉，省电也避免后台误触发
        shakeLog = new ShakeLog(this);
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
        if (id == R.id.nav_tools) {
            target = lazyTools();
        } else if (id == R.id.nav_config) {
            target = lazyConfig();
        } else if (id == R.id.nav_reports) {
            target = lazyReports();
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

    private Fragment lazyTools() {
        if (toolsFragment == null) toolsFragment = new ToolsFragment();
        return toolsFragment;
    }

    private Fragment lazyConfig() {
        if (configFragment == null) configFragment = new ConfigFragment();
        return configFragment;
    }

    private Fragment lazyReports() {
        if (reportsFragment == null) reportsFragment = new ReportsFragment();
        return reportsFragment;
    }

    // 右下角加号：随手开个新会话就去跑任务
    public void onFabClick(View v) {
        String id = AgentBackend.get().createSession("快速任务", null);
        Intent i = new Intent(this, SessionDetailActivity.class);
        i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, id);
        startActivity(i);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_about) {
            AboutDialog.show(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
