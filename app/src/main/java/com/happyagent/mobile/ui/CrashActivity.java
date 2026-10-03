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
        ThemeUtil.apply(this);
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
        Button btnGithub = findViewById(R.id.btn_github_issue);

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

        btnGithub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 一键发 GitHub：先把日志全文复制进剪贴板，再打开预填了日志的 issue 新页
                if (!copy(logBox.getText().toString())) {
                    Toast.makeText(CrashActivity.this, "复制日志失败", Toast.LENGTH_SHORT).show();
                }
                openGithubIssue(logBox.getText().toString());
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

    // 一键发 GitHub：打开预填的 issue 新页（标题+版本+设备信息进 URL；完整日志在剪贴板，粘贴进正文即可）
    private void openGithubIssue(String log) {
        try {
            String ver = "";
            try {
                ver = " v" + getApplicationContext().getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            String head = log.length() > 400 ? log.substring(0, 400) : log;
            String title = java.net.URLEncoder.encode("HappyAgent 崩溃反馈" + ver + " (Android "
                    + android.os.Build.VERSION.RELEASE + ")", "UTF-8");
            String body = java.net.URLEncoder.encode("设备: " + android.os.Build.MANUFACTURER + " "
                    + android.os.Build.MODEL + "（API " + android.os.Build.VERSION.SDK_INT + "）\n"
                    + "构建: " + android.os.Build.FINGERPRINT + "\n\n"
                    + "完整日志已复制在剪贴板，粘贴到下面即可；这是自动截断的头部：\n```\n"
                    + head + "\n...\n```\n", "UTF-8");
            String url = "https://github.com/Damianjiang/happyagent/issues/new"
                    + "?title=" + title + "&body=" + body;
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(CrashActivity.this,
                    "已打开 GitHub 反馈页（日志在剪贴板，粘贴进正文提交）",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(CrashActivity.this, "打不开浏览器，请手动复制日志发到 GitHub issues",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String manualReport() {
        return "=== AGENT CRASH LOG ===\n"
                + "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n"
                + "系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n";
    }
}
