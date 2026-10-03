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

// 摇一摇记日志：加速传感器检测强摇晃时导出当前系统/agent 状态日志。
// 仅开关开时由 MainActivity 在 onResume/onPause 注册传感器。
public final class ShakeLog {

    private static final String TAG = "ShakeLog";
    // 触发阈值（m/s²）；仅明显摇晃才过
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
                // 扣掉重力后的加速度模长，比绝对模长更能反映晃动
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

    // 只在确实注册过才摘，避免无传感器/SENSOR_SERVICE 缺失的设备 onPause NPE
    public void stop() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(listener);
    }

    // 传感器回调在主线程；把写日志（含读 AgentBackend，可能同步等 5 秒）丢后台，避免卡主线程
    private void dumpAndToast() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String path = writeReport();
                Log.i(TAG, "shake-log exported to " + path);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(ctx, "已导出日志：\n" + path,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private String writeReport() {
        File f = new File(ctx.getFilesDir(), "shake-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                        .format(new Date()) + ".txt");
        try {
            StringBuilder body = new StringBuilder();
            body.append("=== Happy Agent 摇一摇日志 ===\n")
               .append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date())).append("\n")
               .append("系统: Android ").append(android.os.Build.VERSION.RELEASE)
                            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
               .append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
                            .append(android.os.Build.MODEL).append('\n');
            // 读 agent 快照（后台线程，等 5 秒也 OK，不卡界面）
            try {
                body.append("会话数: ").append(AgentBackend.get().getSessions().size()).append('\n');
            } catch (Exception e) {
                body.append("会话数: （读取失败 ").append(e.getMessage()).append(")\n");
            }
            try {
                body.append("配置: ").append(AgentBackend.get().getConfig()).append('\n');
            } catch (Exception e) {
                body.append("配置: （读取失败 ").append(e.getMessage()).append(")\n");
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(body.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "write shake-log failed", e);
        }
        return f.getAbsolutePath();
    }
}
