package com.happyagent.mobile.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// 无障碍服务：供 agent 的 gui_* 工具读当前屏幕、点按、输入。
// 关键点：getRootInActiveWindow 只能在主线程调，而工具在 AgentBackend 工作线程跑，
// 所以用「主线程 Handler + latch 同步等待(带超时)」桥接，防死锁。
// 未开启时 isRunning()=false，工具端返回明确提示（诚实降级，不做假实现）。
public final class GuardService extends AccessibilityService {

    private static volatile GuardService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static GuardService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只用于保活/事件类型注册；真正的节点读取按需从 getRootInActiveWindow 拿
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // 主线程同步跑一段，超时防死锁；worker 线程调用
    public String runOnMainTimed(MainJob job, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = job.doIt();
                } catch (Exception e) {
                    out[0] = "出错: " + e.getMessage();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) return "（主线程操作超时）";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "（已取消）";
        }
        return out[0] == null ? "" : out[0];
    }

    public interface MainJob {
        String doIt();
    }

    // —— 供 gui_* 工具经 runOnMainTimed 调用的具体实现 ——
    // dumpScreen 也必须走主线程桥接：getRootInActiveWindow 只在主线程安全，
    // 工具在工作线程分发，直接调会在部分机型挂起/抛异常（历史卡死点）。
    public String dumpScreen() {
        return runOnMainTimed(new MainJob() {
            @Override
            public String doIt() {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    return "（无前台窗口：无障碍服务未启用，或当前无可见界面）";
                }
                try {
                    StringBuilder sb = new StringBuilder();
                    int[] cnt = new int[]{0};
                    walk(root, 0, sb, cnt);
                    return sb.length() == 0 ? "（当前屏幕无可交互节点）" : sb.toString();
                } finally {
                    root.recycle();
                }
            }
        }, 8000);
    }

    private void walk(AccessibilityNodeInfo n, int depth, StringBuilder out, int[] cnt) {
        if (n == null || cnt[0] >= 60) return;
        CharSequence tx = n.getText();
        CharSequence desc = n.getContentDescription();
        String label = (tx != null ? tx.toString() : (desc != null ? desc.toString() : "")).trim();
        boolean interactable = n.isClickable() || n.isEditable() || n.isCheckable() || n.isFocusable();
        if (!interactable && label.isEmpty()) {
            recurse(n, depth, out, cnt);
            return;
        }
        for (int i = 0; i < depth && i < 24; i++) out.append(' ');
        String kind = n.isEditable() ? "可输入" : (n.isCheckable() ? "可勾选" : "可点");
        out.append("[").append(cnt[0]).append("] ").append(kind)
                .append(" · ").append(label.isEmpty() ? "(图标)" : label).append('\n');
        cnt[0]++;
        recurse(n, depth, out, cnt);
    }

    private void recurse(AccessibilityNodeInfo n, int depth, StringBuilder out, int[] cnt) {
        if (depth > 30) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                try {
                    walk(ch, depth + 1, out, cnt);
                } finally {
                    ch.recycle();
                }
            }
        }
    }

    // 按文本点按：找第一个文本包含 query 的可点节点
    public String clickByText(final String query) {
        return runOnMainTimed(new MainJob() {
            @Override
            public String doIt() {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return "（无前台窗口）";
                AccessibilityNodeInfo hit = findClickableByLabel(root, query);
                if (hit == null) return "未找到文本含「" + query + "」的可点元素。用 gui_dump 先看当前屏幕。";
                boolean ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return ok ? "已点「" + query + "」" : "点按失败（元素可能已失效，请重新 gui_dump）";
            }
        }, 8000);
    }

    private AccessibilityNodeInfo findClickableByLabel(AccessibilityNodeInfo n, String q) {
        if (n == null) return null;
        CharSequence tx = n.getText();
        CharSequence desc = n.getContentDescription();
        String label = (tx != null ? tx.toString() : (desc != null ? desc.toString() : ""));
        if (n.isClickable() && label.toLowerCase().contains(q.toLowerCase())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo r = findClickableByLabel(ch, q);
            if (r != null) return r;
        }
        return null;
    }

    // 向可编辑框输入（聚焦后 ACTION_SET_TEXT）
    public String typeText(final String text) {
        return runOnMainTimed(new MainJob() {
            @Override
            public String doIt() {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return "（无前台窗口）";
                AccessibilityNodeInfo ed = findEditable(root);
                if (ed == null) return "当前屏幕没有可输入框。用 gui_dump 确认，或先点进输入框。";
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean ok = ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                return ok ? "已向可输入框写入文本" : "写入失败（元素可能已失效）";
            }
        }, 8000);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo r = findEditable(ch);
            if (r != null) return r;
        }
        return null;
    }
}
