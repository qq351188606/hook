package com.zhongbao.orderhook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Broadcaster {

    private static final String TAG = "ZBHOOK";
    private static volatile Broadcaster INSTANCE;

    private String httpUrl = "";
    private String grabCallbackUrl = "";
    private String broadcastAction = "com.zhongbao.orderhook.ORDER_CAPTURED";
    private String grabAction = "com.zhongbao.orderhook.GRAB_ORDER";
    private boolean fileEnabled = true;
    private boolean httpEnabled = false;
    private boolean broadcastEnabled = true;
    private boolean jsBridgeEnabled = false;

    private Context appContext;
    private String outputDir;

    private final ConcurrentLinkedQueue<String[]> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final Handler retryHandler;
    private final HandlerThread retryThread;

    private volatile ScriptBridge scriptBridge;

    public interface GrabOrderCallback {
        void onGrabOrder(String orderJson);
    }

    private volatile GrabOrderCallback grabCallback;

    public static Broadcaster getInstance() {
        if (INSTANCE == null) {
            synchronized (Broadcaster.class) {
                if (INSTANCE == null) INSTANCE = new Broadcaster();
            }
        }
        return INSTANCE;
    }

    private Broadcaster() {
        retryThread = new HandlerThread("zb-retry");
        retryThread.start();
        retryHandler = new Handler(retryThread.getLooper());
        startQueueProcessor();
    }

    public void init(Context context) {
        this.appContext = context;
        loadConfig();
        initOutputDir();
        Log.i(TAG, "Broadcaster 初始化完成 | 文件=" + fileEnabled + " HTTP=" + httpEnabled
                + " Broadcast=" + broadcastEnabled + " JS=" + jsBridgeEnabled
                + " queueProcessor=就绪");
    }

    private void loadConfig() {
        try {
            if (appContext == null) return;
            android.content.SharedPreferences sp = appContext.getSharedPreferences("zb_config", Context.MODE_PRIVATE);
            httpUrl = sp.getString("http_url", "");
            grabCallbackUrl = sp.getString("grab_callback_url", "");
            broadcastAction = sp.getString("broadcast_action", "com.zhongbao.orderhook.ORDER_CAPTURED");
            grabAction = sp.getString("grab_action", "com.zhongbao.orderhook.GRAB_ORDER");
            fileEnabled = sp.getBoolean("file_enabled", true);
            httpEnabled = sp.getBoolean("http_enabled", !httpUrl.isEmpty());
            broadcastEnabled = sp.getBoolean("broadcast_enabled", true);
            jsBridgeEnabled = sp.getBoolean("js_bridge_enabled", false);
        } catch (Throwable t) {
            Log.e(TAG, "loadConfig 失败", t);
        }
    }

    private void initOutputDir() {
        try {
            outputDir = "/sdcard/zhongbao_order/pushed";
            new File(outputDir).mkdirs();
        } catch (Throwable t) {
            outputDir = "/sdcard/zhongbao_order";
        }
    }

    // ================================================================
    // 核心方法: 广播订单到所有启用的通道
    // ================================================================

    public void broadcastOrder(String tag, String url, String json) {
        if (json == null || json.length() < 50) return;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(new Date());
        String safeUrl = safe(url);
        String meta = "{\"tag\":\"" + tag + "\","
                + "\"url\":\"" + safeUrl + "\","
                + "\"timestamp\":\"" + timestamp + "\","
                + "\"app\":\"zhongbao_hook\"}";

        // 0) JS Bridge (直接在APP内执行用户脚本判断)
        if (jsBridgeEnabled && scriptBridge != null) {
            try {
                scriptBridge.onOrderCaptured(tag, url, json);
            } catch (Throwable t) {
                Log.e(TAG, "JS Bridge 调用失败", t);
            }
        }

        // 1) 文件
        if (fileEnabled) broadcastToFile(tag, timestamp, json);

        // 2) HTTP POST (加入队列, 异步推送, 支持重试)
        if (httpEnabled && !httpUrl.isEmpty()) {
            pendingQueue.offer(new String[]{httpUrl, meta, json});
            queueSize.incrementAndGet();
        }

        // 3) Broadcast
        if (broadcastEnabled && appContext != null) broadcastToBroadcast(tag, url, json);
    }

    // ================================================================
    // 消息队列处理 (带重试)
    // ================================================================

    private void startQueueProcessor() {
        retryHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] item = pendingQueue.poll();
                    if (item != null) {
                        queueSize.decrementAndGet();
                        broadcastToHttp(item[0], item[1], item[2]);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "队列处理异常", t);
                }
                retryHandler.postDelayed(this, 100);
            }
        });
    }

    // ================================================================
    // 通道1: 本地文件
    // ================================================================

    private void broadcastToFile(String tag, String timestamp, String json) {
        try {
            String safeTag = tag.replaceAll("[^a-zA-Z0-9_]", "_");
            File f = new File(outputDir, timestamp + "_" + safeTag + ".json");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(Charset.forName("UTF-8")));
            fos.close();
            Log.i(TAG, "[文件] " + f.getAbsolutePath() + " (" + json.length() + "字节)");
        } catch (Throwable t) {
            Log.e(TAG, "文件推送失败", t);
        }
    }

    // ================================================================
    // 通道2: HTTP POST (带重试)
    // ================================================================

    private void broadcastToHttp(String url, String meta, String orderJson) {
        int maxRetry = 2;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(2000L * attempt);

                String payload = "{\"meta\":" + meta + ",\"order\":" + orderJson + "}";
                byte[] body = payload.getBytes(Charset.forName("UTF-8"));

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                conn.setRequestProperty("X-ZB-Source", "zhongbao_hook");
                conn.setRequestProperty("X-ZB-Version", "2.0");

                if (!isNetworkAvailable()) {
                    Log.w(TAG, "[HTTP] 网络不可用");
                    return;
                }

                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String response = "";
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        response = sb.toString();
                    } catch (Throwable ignore) {}
                    Log.i(TAG, "[HTTP] ✓ 成功 code=" + code);

                    checkGrabResponse(response, orderJson);
                } else {
                    Log.w(TAG, "[HTTP] ✗ 失败 code=" + code + " (重试 " + attempt + "/" + maxRetry + ")");
                    if (attempt == maxRetry) {
                        Log.w(TAG, "[HTTP] 达到最大重试次数, 放弃");
                    }
                }
                conn.disconnect();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "[HTTP] 异常 (重试 " + attempt + "/" + maxRetry + "): " + t.getMessage());
                if (attempt == maxRetry) {
                    Log.e(TAG, "HTTP 推送最终失败", t);
                }
            }
        }
    }

    private void checkGrabResponse(String response, String originalOrder) {
        try {
            if (response == null || response.isEmpty()) return;
            JSONObject resp = new JSONObject(response);
            boolean grab = resp.optBoolean("should_grab", false);
            if (grab) {
                Log.i(TAG, "[HTTP] ← 脚本返回: should_grab=true 触发抢单!");
                String orderFromResp = resp.optString("order", originalOrder);
                if (grabCallback != null) {
                    grabCallback.onGrabOrder(orderFromResp);
                }
                broadcastGrabOrder(orderFromResp);
            } else {
                String msg = resp.optString("msg", "");
                if (!msg.isEmpty()) Log.d(TAG, "[HTTP] ← 脚本返回: " + msg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析脚本响应失败: " + response, t);
        }
    }

    // ================================================================
    // 通道3: Android Broadcast
    // ================================================================

    private void broadcastToBroadcast(String tag, String url, String json) {
        try {
            Intent intent = new Intent(broadcastAction);
            intent.putExtra("tag", tag);
            intent.putExtra("url", url);
            intent.putExtra("json", json);
            intent.putExtra("timestamp", System.currentTimeMillis());
            intent.putExtra("source", "zhongbao_hook");
            if (appContext != null) {
                appContext.sendBroadcast(intent);
                Log.i(TAG, "[Broadcast] " + broadcastAction);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Broadcast 失败", t);
        }
    }

    // ================================================================
    // 抢单回传通道
    // ================================================================

    public void broadcastGrabOrder(String orderJson) {
        try {
            Intent intent = new Intent(grabAction);
            intent.putExtra("action", "GRAB_ORDER");
            intent.putExtra("order", orderJson);
            intent.putExtra("timestamp", System.currentTimeMillis());
            if (appContext != null) appContext.sendBroadcast(intent);
            Log.i(TAG, "[抢单回传] Broadcast 已发送");
        } catch (Throwable t) {
            Log.w(TAG, "抢单回传失败", t);
        }
    }

    public void grabOrderViaCallback(String orderJson) {
        if (grabCallback != null) {
            grabCallback.onGrabOrder(orderJson);
        }
    }

    public void setGrabCallback(GrabOrderCallback callback) {
        this.grabCallback = callback;
    }

    // ================================================================
    // JS Bridge 集成
    // ================================================================

    public void setScriptBridge(ScriptBridge bridge) {
        this.scriptBridge = bridge;
        this.jsBridgeEnabled = (bridge != null);
        saveConfig();
    }

    public ScriptBridge getScriptBridge() {
        return scriptBridge;
    }

    // ================================================================
    // 配置管理
    // ================================================================

    public void setHttpUrl(String url) {
        this.httpUrl = url;
        this.httpEnabled = (url != null && !url.isEmpty());
        saveConfig();
    }

    public void setGrabCallbackUrl(String url) {
        this.grabCallbackUrl = url;
        saveConfig();
    }

    public void setEnabled(boolean file, boolean http, boolean broadcast, boolean js) {
        this.fileEnabled = file;
        this.httpEnabled = http;
        this.broadcastEnabled = broadcast;
        this.jsBridgeEnabled = js;
        saveConfig();
    }

    public String getHttpUrl() { return httpUrl; }
    public String getGrabCallbackUrl() { return grabCallbackUrl; }
    public boolean isFileEnabled() { return fileEnabled; }
    public boolean isHttpEnabled() { return httpEnabled; }
    public boolean isBroadcastEnabled() { return broadcastEnabled; }
    public boolean isJsBridgeEnabled() { return jsBridgeEnabled; }
    public int getQueueSize() { return queueSize.get(); }

    private void saveConfig() {
        try {
            if (appContext == null) return;
            android.content.SharedPreferences.Editor ed = appContext.getSharedPreferences("zb_config", Context.MODE_PRIVATE).edit();
            ed.putString("http_url", httpUrl);
            ed.putString("grab_callback_url", grabCallbackUrl);
            ed.putString("broadcast_action", broadcastAction);
            ed.putString("grab_action", grabAction);
            ed.putBoolean("file_enabled", fileEnabled);
            ed.putBoolean("http_enabled", httpEnabled);
            ed.putBoolean("broadcast_enabled", broadcastEnabled);
            ed.putBoolean("js_bridge_enabled", jsBridgeEnabled);
            ed.apply();
        } catch (Throwable t) {
            Log.e(TAG, "保存配置失败", t);
        }
    }

    private boolean isNetworkAvailable() {
        try {
            if (appContext == null) return true;
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", "\\n").substring(0, Math.min(s.length(), 200));
    }
}