package com.happyagent.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

// 上下文用量环形进度：外圈底环 + 进度弧 + 中心百分比文字。
// 用量 >75% 变警示色、>90% 变错误色（与整体状态色一致），点按弹用量明细（SessionDetailActivity 处理）。
public class TokenRingView extends View {

    private float progress;        // 0..1
    private String percentText = "0";
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TokenRingView(Context ctx) {
        super(ctx);
        int d = (int) ctx.getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(3f * d);
        trackPaint.setColor(0x33808080);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(3f * d);
        textPaint.setTextSize(9f * d);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // 用 0..100 的百分比刷新（主题色由调用方传入）
    public void update(float percent, int color) {
        progress = Math.max(0f, Math.min(1f, percent / 100f));
        percentText = String.valueOf((int) Math.round(percent));
        arcPaint.setColor(color);
        textPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float size = Math.min(getWidth(), getHeight());
        float radius = size / 2f - 4f * d;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(oval, 0, 360, false, trackPaint);
        if (progress > 0.005f) {
            canvas.drawArc(oval, -90, 360 * progress, false, arcPaint);
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(percentText, cx, baseline, textPaint);
    }
}
