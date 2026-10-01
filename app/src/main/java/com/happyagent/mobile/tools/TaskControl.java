package com.happyagent.mobile.tools;

// ReAct 循环的协作式控制：取消、暂停、继续。agent 每步自查，线程被 Future.cancel 中断时也应停。
public final class TaskControl {

    private final Object pauseLock = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
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
