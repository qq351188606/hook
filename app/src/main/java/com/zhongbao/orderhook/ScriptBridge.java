package com.zhongbao.orderhook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptBridge {

    private static final String TAG = "ZBHOOK";

    private Context appContext;
    private Handler scriptHandler;
    private HandlerThread scriptThread;

    private String scriptPath = "";
    private String scriptUrl = "";
    private String inlineScript = "";
    private long timeoutMs = 3000;

    private String currentScript = "";
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile Object jsEngine;
    private volatile Object webView;

    public interface OrderProcessor {
        boolean shouldGrab(String orderJson);
    }

    public ScriptBridge(Context context) {
        this.appContext = context;
        scriptThread = new HandlerThread("zb-script");
        scriptThread.start();
        scriptHandler = new Handler(scriptThread.getLooper());
    }

    public void init() {
        if (initialized.getAndSet(true)) return;
        scriptHandler.post(() -> {
            try { loadScript(); initJsEngine(); } catch (Throwable t) { Log.e(TAG, "ScriptBridge init fail", t); }
        });
    }

    public void setScriptPath(String path) { this.scriptPath = path; initialized.set(false); }
    public void setScriptUrl(String url) { this.scriptUrl = url; initialized.set(false); }
    public void setInlineScript(String script) { this.inlineScript = script; initialized.set(false); }

    private void loadScript() {
        if (inlineScript != null && !inlineScript.isEmpty()) { currentScript = inlineScript; return; }
        if (scriptPath != null && !scriptPath.isEmpty()) {
            try { File f = new File(scriptPath); if (f.exists()) { StringBuilder sb = new StringBuilder(); BufferedReader br = new BufferedReader(new FileReader(f)); String line; while ((line = br.readLine()) != null) sb.append(line).append("\n"); br.close(); currentScript = sb.toString(); return; } } catch (Throwable t) {}
        }
        if (scriptUrl != null && !scriptUrl.isEmpty()) {
            try { HttpURLConnection conn = (HttpURLConnection) new URL(scriptUrl).openConnection(); conn.setConnectTimeout(5000); conn.setReadTimeout(10000); conn.setRequestMethod("GET"); int code = conn.getResponseCode(); if (code >= 200 && code < 300) { BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line).append("\n"); br.close(); currentScript = sb.toString(); return; } conn.disconnect(); } catch (Throwable t) {}
        }
        currentScript = "";
    }

    private void initJsEngine() {
        try { Class.forName("android.webkit.WebView"); initWebViewEngine(); } catch (Throwable t1) {
            try { initScriptEngine(); } catch (Throwable t2) {}
        }
    }

    private void initWebViewEngine() {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(appContext);
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setBlockNetworkLoads(true);
            wv.evaluateJavascript("(function(){window.grabResult=null;window.onOrder=function(json){try{var o=JSON.parse(json);var r={shouldGrab:false,reason:'',order:o};var f=o.deliveryFee||o.fee||o.income||0;var t=o.tip||0;var d=o.deliveryDistance||o.distance||o.totalDistance||0;if(parseFloat(f)+parseFloat(t)>=8&&parseInt(d)<=5000){r.shouldGrab=true;r.reason='命中';}else{r.reason='未命中';}window.grabResult=JSON.stringify(r);}catch(e){window.grabResult=JSON.stringify({shouldGrab:false,reason:'JS error:'+e.message});}}})();", null);
            if (currentScript != null && !currentScript.isEmpty()) wv.evaluateJavascript(currentScript, null);
            webView = wv;
        } catch (Throwable t) {}
    }

    private void initScriptEngine() {
        try {
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("JavaScript");
            if (engine == null) engine = manager.getEngineByName("js");
            if (engine == null) return;
            engine.eval("(function(){var S={};S.grabResult=null;S.onOrder=function(json){try{var o=JSON.parse(json);var r={shouldGrab:false,reason:'',order:o};var f=o.deliveryFee||o.fee||o.income||0;var t=o.tip||0;var d=o.deliveryDistance||o.distance||o.totalDistance||0;if(parseFloat(f)+parseFloat(t)>=8&&parseInt(d)<=5000){r.shouldGrab=true;}S.grabResult=JSON.stringify(r);return S.grabResult;}catch(e){S.grabResult=JSON.stringify({shouldGrab:false,reason:'err'});return S.grabResult;}};return S;})();");
            if (currentScript != null && !currentScript.isEmpty()) engine.eval(currentScript);
            jsEngine = engine;
        } catch (Throwable t) {}
    }

    public void onOrderCaptured(String tag, String url, String orderJson) {
        if (orderJson == null || orderJson.length() < 50) return;
        scriptHandler.post(() -> {
            try {
                long start = System.currentTimeMillis();
                JsResult result = processOrderInJs(orderJson);
                long elapsed = System.currentTimeMillis() - start;
                if (result.grab) {
                    Log.i(TAG, "[ScriptBridge] ★ JS判断抢单 耗时=" + elapsed + "ms");
                    Broadcaster.getInstance().broadcastGrabOrder(orderJson);
                }
            } catch (Throwable t) { Log.e(TAG, "ScriptBridge process fail", t); }
        });
    }

    private static class JsResult { boolean grab; String reason; JsResult(boolean g, String r) { this.grab = g; this.reason = r; } }

    private JsResult processOrderInJs(String orderJson) {
        AtomicReference<JsResult> ref = new AtomicReference<>(new JsResult(false, "未就绪"));
        if (webView != null) {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                final String[] buf = {null};
                String escaped = orderJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
                ((android.webkit.WebView) webView).evaluateJavascript("(function(){try{var r=window.onOrder('" + escaped + "');window.__cb=r;}catch(e){window.__cb=JSON.stringify({shouldGrab:false,reason:'err'});}})();", value -> { buf[0] = value; latch.countDown(); });
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return new JsResult(false, "超时");
                if (buf[0] != null) {
                    JSONObject obj = new JSONObject(buf[0]);
                    return new JsResult(obj.optBoolean("shouldGrab", false), obj.optString("reason", ""));
                }
            } catch (Throwable t) {}
        } else if (jsEngine != null) {
            try {
                javax.script.ScriptEngine engine = (javax.script.ScriptEngine) jsEngine;
                Object sb = engine.get("ScriptBridge");
                if (sb != null) {
                    java.lang.reflect.Method m = sb.getClass().getMethod("onOrder", String.class);
                    Object raw = m.invoke(sb, orderJson);
                    if (raw instanceof String) { JSONObject obj = new JSONObject((String) raw); return new JsResult(obj.optBoolean("shouldGrab", false), obj.optString("reason", "")); }
                }
            } catch (Throwable t) {}
        }
        return localFallback(orderJson);
    }

    private JsResult localFallback(String orderJson) {
        try {
            JSONObject o = new JSONObject(orderJson);
            double fee = o.optDouble("deliveryFee", o.optDouble("fee", o.optDouble("income", o.optDouble("price", 0))));
            double tip = o.optDouble("tip", 0);
            int dist = o.optInt("deliveryDistance", o.optInt("distance", o.optInt("totalDistance", 0)));
            boolean grab = (fee + tip >= 8 && dist <= 5000);
            return new JsResult(grab, "本地规则: " + (fee + tip) + "元 " + dist + "m");
        } catch (Throwable t) { return new JsResult(false, "解析失败"); }
    }

    public void reloadScript() { initialized.set(false); scriptHandler.post(() -> { try { loadScript(); initJsEngine(); initialized.set(true); } catch (Throwable t) {} }); }

    public String getScriptInfo() {
        return "脚本长度:" + (currentScript != null ? currentScript.length() : 0) + "字节 引擎:" + (webView != null ? "WebView" : (jsEngine != null ? "ScriptEngine" : "无"));
    }

    public void destroy() { try { if (webView != null) ((android.webkit.WebView) webView).destroy(); if (scriptThread != null) scriptThread.quitSafely(); } catch (Throwable t) {} }
}