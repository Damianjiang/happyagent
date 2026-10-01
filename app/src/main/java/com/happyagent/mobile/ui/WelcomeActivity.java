package com.happyagent.mobile.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.Prefs;

// 首次打开的欢迎页：亮出 logo/要点 + 开源免费警示，点"开始使用"落盘标记并进主界面
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);
        findViewById(R.id.welcome_start).setOnClickListener(v -> enterApp());
    }

    private void enterApp() {
        new Prefs(this).putBoolean(Prefs.KEY_FIRST_LAUNCH, true);
        finish();   // 回到背后已铺好的主界面
    }
}
