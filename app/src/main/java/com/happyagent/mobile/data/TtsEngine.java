package com.happyagent.mobile.data;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.happyagent.mobile.HappyAgentApplication;

import java.util.Locale;

// 朗读引擎：任务完成后把 AI 回复念出来（设置页可开关，默认关）。
// 单例持有一个系统 TTS；系统没有语音包时静默跳过（诚实降级，不崩不卡）。
public final class TtsEngine {

    private static final String TAG = "TtsEngine";
    private static volatile TtsEngine instance;
    private final Object lock = new Object();
    private TextToSpeech tts;
    private boolean available = true;

    private TtsEngine() {}

    public static TtsEngine get() {
        if (instance == null) {
            synchronized (TtsEngine.class) {
                if (instance == null) instance = new TtsEngine();
            }
        }
        return instance;
    }

    // Application 启动时尽早调；TTS 初始化是异步的，没就绪时 speak 直接跳过
    public void init(Context app) {
        synchronized (lock) {
            if (tts != null) return;
            try {
                tts = new TextToSpeech(app.getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        tts.setLanguage(Locale.CHINA);   // 中文不可用则退回系统默认语音
                    } else {
                        available = false;
                        Log.w(TAG, "TTS init 失败（状态 " + status + "），朗读不可用");
                    }
                });
            } catch (Exception e) {
                available = false;
                tts = null;
                Log.w(TAG, "TTS init 异常", e);
            }
        }
    }

    // 开关在设置页（Prefs）；没开就什么都不念
    public boolean enabled() {
        return new Prefs(HappyAgentApplication.get()).getBoolean(Prefs.KEY_TTS_ON, false);
    }

    // 念一段文本（清掉上一段再念）
    public void speak(String text) {
        if (!enabled() || text == null || text.trim().isEmpty()) return;
        synchronized (lock) {
            if (tts == null || !available) return;
        }
        try {
            tts.speak(sanitize(text), TextToSpeech.QUEUE_FLUSH, null, "happy-agent");
        } catch (Exception e) {
            Log.w(TAG, "speak 失败", e);
        }
    }

    public void stop() {
        synchronized (lock) {
            if (tts != null && available) {
                try {
                    tts.stop();
                } catch (Exception ignored) {}
            }
        }
    }

    // 去掉 markdown / 代码围栏噪音，念出来更自然
    private static String sanitize(String t) {
        String s = t.replace("`", "").replace("*", "").replace("#", "");
        s = s.replace("\n\n", "。").replace('\n', ' ');
        return s.trim();
    }
}
