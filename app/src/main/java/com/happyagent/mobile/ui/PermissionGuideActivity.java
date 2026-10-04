package com.happyagent.mobile.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.happyagent.mobile.R;

// 权限引导页：逐项展示，点按钮跳系统设置给。简洁不花哨。
public class PermissionGuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_permission_guide);

        setupMic();
        setupOverlay();
        setupStorage();

        findViewById(R.id.perm_skip).setOnClickListener(v -> finish());
    }

    @Override
    public void finish() {
        // 不管走哪条路结束引导页，都标记"首次启动已完成"
        new com.happyagent.mobile.data.Prefs(this)
                .putBoolean(com.happyagent.mobile.data.Prefs.KEY_FIRST_LAUNCH, true);
        super.finish();
    }

    private void setupMic() {
        int code = ContextCompat.checkSelfPermission(this, "android.permission.RECORD_AUDIO");
        TextView status = findViewById(R.id.perm_mic_status);
        Button btn = findViewById(R.id.perm_mic_btn);
        if (code == PackageManager.PERMISSION_GRANTED) {
            status.setText("已授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_done));
            btn.setVisibility(android.view.View.GONE);
        } else {
            status.setText("未授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_paused));
            btn.setOnClickListener(v -> requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 101));
        }
    }

    private void setupOverlay() {
        boolean granted = Build.VERSION.SDK_INT >= 23
                ? Settings.canDrawOverlays(this)
                : true;
        TextView status = findViewById(R.id.perm_overlay_status);
        Button btn = findViewById(R.id.perm_overlay_btn);
        if (granted) {
            status.setText("已授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_done));
            btn.setVisibility(android.view.View.GONE);
        } else {
            status.setText("未授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_paused));
            btn.setOnClickListener(v -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            });
        }
    }

    private void setupStorage() {
        String perm;
        boolean granted;
        if (Build.VERSION.SDK_INT >= 33) {
            perm = "android.permission.READ_MEDIA_IMAGES";
            granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
        } else {
            perm = "android.permission.READ_EXTERNAL_STORAGE";
            granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
        }
        TextView status = findViewById(R.id.perm_storage_status);
        Button btn = findViewById(R.id.perm_storage_btn);
        if (granted) {
            status.setText("已授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_done));
            btn.setVisibility(android.view.View.GONE);
        } else {
            status.setText("未授权");
            status.setTextColor(ContextCompat.getColor(this, R.color.status_paused));
            btn.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{perm}, 101);
                } else {
                    requestPermissions(new String[]{perm}, 101);
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == 101) {
            setupMic();
            setupStorage();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupOverlay();
    }
}
