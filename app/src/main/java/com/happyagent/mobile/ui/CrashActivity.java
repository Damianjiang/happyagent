package com.happyagent.mobile.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.happyagent.mobile.CrashHandler;
import com.happyagent.mobile.R;

import java.text.SimpleDateFormat;
import java.util.Date;

// 崩溃页：程序挂了就自动跳到这，日志可整段复制/分享，再一键回首页
public class CrashActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT = "report";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        String report = getIntent().getStringExtra(EXTRA_REPORT);
        if (report == null || report.isEmpty()) report = manualReport();

        EditText logBox = findViewById(R.id.crash_log);
        logBox.setText(report);

        // 点日志区本身也能复制
        logBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (copy(logBox.getText().toString())) {
                    Toast.makeText(CrashActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Button btnCopy = findViewById(R.id.btn_copy_log);
        Button btnShare = findViewById(R.id.btn_share_log);
        Button btnHome = findViewById(R.id.btn_home);

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (copy(logBox.getText().toString())) {
                    Toast.makeText(CrashActivity.this, "已复制，直接发给开发就行", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(CrashActivity.this, "复制失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(logBox.getText().toString());
            }
        });

        btnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashHandler.finishAndGoHome(CrashActivity.this);
                finish();
            }
        });
    }

    private boolean copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        cm.setPrimaryClip(ClipData.newPlainText("happy-agent-crash-log", text));
        return true;
    }

    private void share(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "HappyAgent崩溃日志");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "分享崩溃日志"));
        } catch (Exception ignored) {
        }
    }

    private String manualReport() {
        return "=== AGENT CRASH LOG ===\n"
                + "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n"
                + "系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n";
    }
}
