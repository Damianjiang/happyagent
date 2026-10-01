package com.happyagent.mobile.ui;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import com.happyagent.mobile.HappyAgentApplication;
import com.happyagent.mobile.data.Prefs;
import com.happyagent.mobile.data.AgentBackend;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 摇一摇记日志：加速传感器检测到一次强摇晃时，把当前系统/agent 状态导出一份日志文件。
// 只有「行为>摇一摇记日志」开关开才注册传感器；由 MainActivity 在 onResume/onPause 挂接。
public final class ShakeLog {

    private static final String TAG = "ShakeLog";
    // 触发阈值（m/s²）：低于手机正常手持抖动，明显摇动才过
    private static final float THRESHOLD = 28f;
    private static final long COOLDOWN_MS = 5000;

    private final Context ctx;
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final SensorEventListener listener;
    private long lastTrigger = 0;

    public ShakeLog(Context context) {
        this.ctx = context.getApplicationContext();
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager == null ? null :
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e.values == null || e.values.length < 3) return;
                float x = e.values[0], y = e.values[1], z = e.values[2];
                // 扣掉重力后的加速度模长，比绝对模长更能反映"晃动"
                float g = 9.81f;
                float ax = x, ay = y - g, az = z;
                float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (mag < THRESHOLD) return;
                long now = System.currentTimeMillis();
                if (now - lastTrigger < COOLDOWN_MS) return;
                lastTrigger = now;
                dumpAndToast();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
    }

    public boolean isSupported() {
        return accelerometer != null;
    }

    public boolean isEnabled() {
        return isSupported()
                && new Prefs(ctx).getBoolean(Prefs.KEY_ENABLE_SHAKE_TO_LOG, false);
    }

    public void start() {
        if (!isEnabled()) return;
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    // 只在确实注册过才摘，避免个别无传感器/SENSOR_SERVICE 缺失的设备在 onPause NPE
    public void stop() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(listener);
    }

    private void dumpAndToast() {
        String path = writeReport();
        Log.i(TAG, "shake-log exported to " + path);
        // Context 没有 runOnUiThread（那是 Activity 的），走主线程 Handler 弹 Toast
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(ctx, "已导出日志：\n" + path,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private String writeReport() {
        File f = new File(ctx.getFilesDir(), "shake-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                        .format(new Date()) + ".txt");
        try {
            String body = "=== Happy Agent 摇一摇日志 ===\n"
                    + "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date()) + "\n"
                    + "系统: Android " + android.os.Build.VERSION.RELEASE
                            + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                    + "设备: " + android.os.Build.MANUFACTURER + " "
                            + android.os.Build.MODEL + "\n"
                    + "会话数: " + AgentBackend.get().getSessions().size() + "\n"
                    + "配置: " + AgentBackend.get().getConfig() + "\n";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(body.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "write shake-log failed", e);
        }
        return f.getAbsolutePath();
    }
}
