package com.happyagent.mobile.tools;

import java.net.HttpURLConnection;

// ReAct 循环的协作式控制：取消、暂停、继续。
// 取消 = 置位 + 断开当前 HTTP 连接（Thread.interrupt 解不开阻塞 read，必须 disconnect 才能中止等待）。
public final class TaskControl {

    private final Object pauseLock = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;
    // 正在阻塞读响应体的连接；cancel() 时断开它
    private volatile HttpURLConnection activeConn;

    public void setConn(HttpURLConnection conn) {
        this.activeConn = conn;
    }

    public void clearConn() {
        this.activeConn = null;
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = activeConn;
        if (c != null) c.disconnect();   // 让 worker 线程里阻塞的 read 抛 SocketException 退出
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public void pause() {
        if (cancelled) return;
        paused = true;
    }

    public void resume() {
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused && !cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // 已被取消，或承载线程被 Future.cancel 打断
    public boolean shouldStop() {
        return cancelled || Thread.interrupted();
    }

    // 暂停时阻塞工作线程，直到 resume/cancel 或被打断
    public void waitForResume() {
        synchronized (pauseLock) {
            while (!cancelled && paused) {
                try {
                    pauseLock.wait(200);
                } catch (InterruptedException e) {
                    return;   // 被打断，交回 shouldStop 判定
                }
            }
        }
    }
}
