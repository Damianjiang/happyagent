package com.happyagent.mobile.ui;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.happyagent.mobile.R;

// 悬浮球：可拖拽小圆球，点按打开主界面。轻量，老安卓 6 不卡。
public class FloatingBallService extends Service {

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private int initWinX, initWinY;
    private int firstRawX, firstRawY;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createBall();
    }

    private void createBall() {
        FrameLayout root = new FrameLayout(this);
        int sz = (int) (44 * getResources().getDisplayMetrics().density);

        TextView tv = new TextView(this);
        tv.setText("\u2726");
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundResource(R.drawable.bg_floating_ball);
        // 悬浮球跟随当前强调色（服务上下文没套强调色主题，按 Prefs 直接画半透明圆）
        try {
            String hex = com.happyagent.mobile.ui.ThemeUtil.accentHex(this);
            if (hex == null) hex = "#E8A317";
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor((0xCC << 24) | android.graphics.Color.parseColor(hex));
            g.setStroke(1, 0x44FFFFFF);
            g.setSize(sz, sz);
            tv.setBackground(g);
        } catch (Exception ignored) {
        }
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(sz, sz);
        root.addView(tv, tlp);

        ball = root;

        int type;
        if (Build.VERSION.SDK_INT >= 26) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 40;
        lp.y = 200;

        wm.addView(ball, lp);

        int touchSlop = (int) (8 * getResources().getDisplayMetrics().density);

        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        firstRawX = (int) e.getRawX();
                        firstRawY = (int) e.getRawY();
                        initWinX = lp.x;
                        initWinY = lp.y;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - firstRawX);
                        int dy = (int) (e.getRawY() - firstRawY);
                        lp.x = initWinX + dx;
                        lp.y = initWinY + dy;
                        wm.updateViewLayout(ball, lp);
                        return true;

                    case MotionEvent.ACTION_UP:
                        int mx = (int) (e.getRawX() - firstRawX);
                        int my = (int) (e.getRawY() - firstRawY);
                        if (Math.abs(mx) < touchSlop && Math.abs(my) < touchSlop) {
                            openLatestSession();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void openLatestSession() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Override
    public void onDestroy() {
        if (ball != null) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
