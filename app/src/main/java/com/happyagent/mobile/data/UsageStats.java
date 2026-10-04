package com.happyagent.mobile.data;

// 会话 token 用量统计：累计输入/输出 + 最近一次请求的上下文窗口（prompt_tokens 即当前窗口大小）。
// 数据来自供应商响应的 usage 字段（没有时按字符数/4 估算），聊天页环形进度实时读取。
public final class UsageStats {

    private static final UsageStats INSTANCE = new UsageStats();

    public static UsageStats get() {
        return INSTANCE;
    }

    private UsageStats() {}

    private long cumulativeInput;
    private long cumulativeOutput;
    private long lastWindow;

    // 供应商返回 usage 时调用（主线程/引擎线程都可能，加锁）
    public synchronized void record(long inputTokens, long outputTokens) {
        if (inputTokens > 0) cumulativeInput += inputTokens;
        if (outputTokens > 0) cumulativeOutput += outputTokens;
        if (inputTokens > 0) lastWindow = inputTokens;   // 最近一次请求的入参量 = 当前窗口
    }

    public synchronized long getCumulativeInput() {
        return cumulativeInput;
    }

    public synchronized long getCumulativeOutput() {
        return cumulativeOutput;
    }

    public synchronized long getLastWindow() {
        return lastWindow;
    }

    public synchronized void reset() {
        cumulativeInput = 0;
        cumulativeOutput = 0;
        lastWindow = 0;
    }

    // 没有 usage 字段时的兜底估算（业界惯例约 4 字符 1 token）
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }
}
