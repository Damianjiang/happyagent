package com.happyagent.mobile.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 本地 Web 服务：在手机上起一个 HTTP 服务（ServerSocket，纯 API23 安全），
// 把 agent 对话暴露给任意浏览器；前台通知常驻，可展示内网/外网地址与端口。
// 页面、IP 查询、agent 调用都走这里，客户端（电脑或手机浏览器）自适应渲染。
public class WebUiService extends Service {

    public static final int PORT = 8177;
    private static final String CHANNEL_ID = "web_service";

    private static volatile WebUiService instance;
    private static volatile String publicIp = "";

    private volatile String lanIp = "";
    private byte[] pageBytes;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running;

    // ---- 静态入口（设置页 / 启动恢复用）----
    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    public static int port() {
        return PORT;
    }

    public static String lanIpStatic() {
        WebUiService i = instance;
        return i == null ? "" : i.lanIp;
    }

    public static String publicIpStatic() {
        return publicIp;
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), WebUiService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx.getApplicationContext(), WebUiService.class));
    }

    // 第一个非回环的站点内 IPv4，即局域网里其它设备连手机用的地址
    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> ias = ni.getInetAddresses();
                while (ias.hasMoreElements()) {
                    InetAddress ia = ias.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress() && ia.isSiteLocalAddress()) {
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---- 生命周期 ----
    @Override
    public IBinder onBind(Intent intent) {
        return null;   // 只做前台常驻，不对外暴露 binder
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 先取本地内网 IP（快），立刻挂前台通知，满足系统对前台服务的时限；网络查询放后台
        lanIp = lanIp();
        startForeground(1, buildNotification());
        if (!running) {
            running = true;
            instance = this;
            pageBytes = readPage();
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            });
            acceptThread.start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    publicIp = doFetchPublicIp();
                }
            }).start();
        }
        return START_NOT_STICKY;   // 被系统回收后不自动拉活，等用户在设置里重开
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();   // 让 accept() 抛异常退出循环
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdown();
        stopForegroundSelf();
        instance = null;
        super.onDestroy();
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(PORT));
            pool = Executors.newFixedThreadPool(4);
            while (running) {
                Socket s = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                });
            }
        } catch (Exception e) {
            // 端口占用等：停掉服务并收掉通知
            running = false;
            stopSelf();
        }
    }

    // ---- HTTP 处理 ----
    private void handle(Socket socket) {
        try {
            BufferedInputStream bin = new BufferedInputStream(socket.getInputStream());
            String reqLine = readLine(bin);
            if (reqLine == null || reqLine.trim().isEmpty()) return;
            String[] rp = reqLine.split(" ");
            String method = rp[0];
            String path = rp.length > 1 ? rp[1] : "/";

            int contentLength = 0;
            String hdr;
            while ((hdr = readLine(bin)) != null && !hdr.isEmpty()) {
                String low = hdr.toLowerCase();
                if (low.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(low.substring(15).trim());
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int r = bin.read(body, off, contentLength - off);
                    if (r < 0) break;
                    off += r;
                }
            }

            Resp resp = route(method, path, new String(body, StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.1 " + resp.code + " " + resp.reason + "\r\n"
                    + "Content-Type: " + resp.contentType + "\r\n"
                    + "Content-Length: " + resp.data.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(resp.data);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Resp route(String method, String path, String body) throws Exception {
        if (path.equals("/") || path.equals("/index.html") || path.equals("/chat")) {
            byte[] data = pageBytes == null ? new byte[0] : pageBytes;
            return new Resp(200, "OK", "text/html; charset=utf-8", data);
        }
        if (path.equals("/api/ipinfo")) {
            JSONObject o = new JSONObject();
            o.put("lan", lanIp);
            o.put("public", publicIp);
            o.put("port", PORT);
            return json(o);
        }
        if (path.equals("/api/state")) {
            Models.Config cfg = AgentBackend.get().getConfig();
            JSONObject o = new JSONObject();
            o.put("provider", cfg.getProvider());
            o.put("model", cfg.model);
            o.put("keyConfigured", cfg.hasKey());
            JSONArray arr = new JSONArray();
            for (Models.Session s : AgentBackend.get().getSessions()) {
                arr.put(new JSONObject().put("id", s.id).put("title", s.title).put("status", s.status));
            }
            o.put("sessions", arr);
            return json(o);
        }
        if (path.equals("/api/ask") && method.equalsIgnoreCase("POST")) {
            JSONObject req = new JSONObject(body);
            String prompt = req.optString("prompt", "");
            String sid = req.optString("sessionId", "");
            Map<String, Object> r = AgentBackend.get().askFreeForm(prompt, sid.isEmpty() ? null : sid);
            JSONObject o = new JSONObject();
            o.put("sessionId", String.valueOf(r.get("sessionId")));
            o.put("status", r.get("status"));
            o.put("running", r.get("running"));
            o.put("answer", r.get("answer"));
            o.put("statusLabel", r.get("statusLabel"));
            return json(o);
        }
        return json(new JSONObject().put("error", "not found").put("path", path), 404, "Not Found");
    }

    private Resp json(JSONObject o) {
        return json(o, 200, "OK");
    }

    private Resp json(JSONObject o, int code, String reason) {
        return new Resp(code, reason, "application/json; charset=utf-8",
                o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- 工具方法 ----
    private static String readLine(BufferedInputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (!any) return null;
        return sb.toString();
    }

    private byte[] readPage() {
        try {
            InputStream in = getApplicationContext().getResources().openRawResource(R.raw.web_chat);
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static String doFetchPublicIp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code == 200) {
                String ip = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8).trim();
                c.disconnect();
                return ip;
            }
            c.disconnect();
            return "";
        } catch (Exception e) {
            return "";   // 取不到外网就留空，界面如实显示"获取中/不可达"
        }
    }

    // ---- 前台通知 ----
    private Notification buildNotification() {
        String url = "http://" + (lanIp.isEmpty() ? "本机" : lanIp) + ":" + PORT;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Web 服务", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);   // API 23~25 无通道
        }
        b.setSmallIcon(R.drawable.ic_stat_web)
                .setContentTitle("Happy Agent · Web 服务")
                .setContentText("运行中 · " + url)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openUrl(url));
        return b.build();
    }

    private PendingIntent openUrl(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundSelf() {
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private static final class Resp {
        final int code;
        final String reason;
        final String contentType;
        final byte[] data;

        Resp(int c, String r, String t, byte[] d) {
            code = c;
            reason = r;
            contentType = t;
            data = d;
        }
    }
}
