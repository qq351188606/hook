package com.zhongbao.orderhook;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private volatile GrabOrderCallback grabCallback;

    public interface GrabOrderCallback {
        void onGrabOrder(String orderJson);
    }

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
        } catch (Throwable t) {}
    }

    private void initOutputDir() {
        try { outputDir = "/sdcard/zhongbao_order/pushed"; new File(outputDir).mkdirs(); } catch (Throwable t) { outputDir = "/sdcard/zhongbao_order"; }
    }

    public void broadcastOrder(String tag, String url, String json) {
        if (json == null || json.length() < 50) return;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(new Date());
        String safeUrl = safe(url);
        String meta = "{\"tag\":\"" + tag + "\",\"url\":\"" + safeUrl + "\",\"timestamp\":\"" + timestamp + "\",\"app\":\"zhongbao_hook\"}";
        if (jsBridgeEnabled && scriptBridge != null) {
            try { scriptBridge.onOrderCaptured(tag, url, json); } catch (Throwable t) {}
        }
        if (fileEnabled) broadcastToFile(tag, timestamp, json);
        if (httpEnabled && !httpUrl.isEmpty()) {
            pendingQueue.offer(new String[]{httpUrl, meta, json});
            queueSize.incrementAndGet();
        }
        if (broadcastEnabled && appContext != null) broadcastToBroadcast(tag, url, json);
    }

    private void startQueueProcessor() {
        retryHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    String[] item = pendingQueue.poll();
                    if (item != null) { queueSize.decrementAndGet(); broadcastToHttp(item[0], item[1], item[2]); }
                } catch (Throwable t) {}
                retryHandler.postDelayed(this, 100);
            }
        });
    }

    private void broadcastToFile(String tag, String timestamp, String json) {
        try {
            String safeTag = tag.replaceAll("[^a-zA-Z0-9_]", "_");
            File f = new File(outputDir, timestamp + "_" + safeTag + ".json");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(StandardCharsets.UTF_8)); fos.close();
        } catch (Throwable t) {}
    }

    private void broadcastToHttp(String url, String meta, String orderJson) {
        int maxRetry = 2;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(2000L * attempt);
                String payload = "{\"meta\":" + meta + ",\"order\":" + orderJson + "}";
                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setDoInput(true);
                conn.setConnectTimeout(5000); conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-ZB-Source", "zhongbao_hook");
                if (!isNetworkAvailable()) return;
                OutputStream os = conn.getOutputStream(); os.write(body); os.flush(); os.close();
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String response = "";
                    try { BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line); br.close(); response = sb.toString(); } catch (Throwable ignore) {}
                    checkGrabResponse(response, orderJson);
                }
                conn.disconnect(); return;
            } catch (Throwable t) { if (attempt == maxRetry) { break; } }
        }
    }

    private void checkGrabResponse(String response, String originalOrder) {
        try {
            if (response == null || response.isEmpty()) return;
            JSONObject resp = new JSONObject(response);
            boolean grab = resp.optBoolean("should_grab", false);
            if (grab) {
                String orderFromResp = resp.optString("order", originalOrder);
                if (grabCallback != null) grabCallback.onGrabOrder(orderFromResp);
                broadcastGrabOrder(orderFromResp);
            }
        } catch (Throwable t) {}
    }

    private void broadcastToBroadcast(String tag, String url, String json) {
        try {
            Intent intent = new Intent(broadcastAction);
            intent.putExtra("tag", tag); intent.putExtra("url", url); intent.putExtra("json", json);
            intent.putExtra("timestamp", System.currentTimeMillis()); intent.putExtra("source", "zhongbao_hook");
            if (appContext != null) appContext.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    public void broadcastGrabOrder(String orderJson) {
        try {
            Intent intent = new Intent(grabAction); intent.putExtra("action", "GRAB_ORDER");
            intent.putExtra("order", orderJson); intent.putExtra("timestamp", System.currentTimeMillis());
            if (appContext != null) appContext.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    public void setGrabCallback(GrabOrderCallback callback) { this.grabCallback = callback; }
    public void setScriptBridge(ScriptBridge bridge) { this.scriptBridge = bridge; this.jsBridgeEnabled = (bridge != null); saveConfig(); }
    public ScriptBridge getScriptBridge() { return scriptBridge; }
    public void setHttpUrl(String url) { this.httpUrl = url; this.httpEnabled = (url != null && !url.isEmpty()); saveConfig(); }
    public void setEnabled(String file, String http, String broadcast, String js) { this.fileEnabled = file; this.httpEnabled = http; this.broadcastEnabled = broadcast; this.jsBridgeEnabled = js; saveConfig(); }
    public String getHttpUrl() { return httpUrl; }
    public boolean isFileEnabled() { return fileEnabled; }
    public boolean isHttpEnabled() { return httpEnabled; }
    public boolean isBroadcastEnabled() { return broadcastEnabled; }
    public boolean isJsBridgeEnabled() { return jsBridgeEnabled; }
    public int getQueueSize() { return queueSize.get(); }

    private void saveConfig() {
        try {
            if (appContext == null) return;
            android.content.SharedPreferences.Editor ed = appContext.getSharedPreferences("zb_config", Context.MODE_PRIVATE).edit();
            ed.putString("http_url", httpUrl); ed.putString("grab_callback_url", grabCallbackUrl);
            ed.putString("broadcast_action", broadcastAction); ed.putString("grab_action", grabAction);
            ed.putBoolean("file_enabled", fileEnabled); ed.putBoolean("http_enabled", httpEnabled);
            ed.putBoolean("broadcast_enabled", broadcastEnabled); ed.putBoolean("js_bridge_enabled", jsBridgeEnabled);
            ed.apply();
        } catch (Throwable t) {}
    }

    private boolean isNetworkAvailable() {
        try { if (appContext == null) return true; ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE); NetworkInfo info = cm.getActiveNetworkInfo(); return info != null && info.isConnected(); } catch (Throwable t) { return true; }
    }

    private String safe(String s) { return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n").substring(0, Math.min(s.length(), 200)); }
}