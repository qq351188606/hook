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
import java.nio.charset.Charset;
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

    public interface OrderProcessor {
        boolean shouldGrab(String orderJson);
        void onOrderProcessed(String orderJson, boolean grab);
    }

    private volatile OrderProcessor processor;

    public ScriptBridge(Context context) {
        this.appContext = context;
        scriptThread = new HandlerThread("zb-script");
        scriptThread.start();
        scriptHandler = new Handler(scriptThread.getLooper());
    }

    public void init() {
        if (initialized.getAndSet(true)) return;
        scriptHandler.post(() -> {
            try {
                loadScript();
                initJsEngine();
                Log.i(TAG, "[ScriptBridge] ✓ 初始化完成, 脚本长度=" + (currentScript != null ? currentScript.length() : 0));
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 初始化失败", t);
            }
        });
    }

    public void setScriptPath(String path) { this.scriptPath = path; initialized.set(false); }
    public void setScriptUrl(String url) { this.scriptUrl = url; initialized.set(false); }
    public void setInlineScript(String script) { this.inlineScript = script; initialized.set(false); }
    public void setTimeout(long ms) { this.timeoutMs = ms; }

    public void setOrderProcessor(OrderProcessor p) { this.processor = p; }

    // ================================================================
    // 加载脚本
    // ================================================================

    private void loadScript() {
        if (inlineScript != null && !inlineScript.isEmpty()) {
            currentScript = inlineScript;
            Log.i(TAG, "[ScriptBridge] 使用内联脚本 (" + inlineScript.length() + "字节)");
            return;
        }
        if (scriptPath != null && !scriptPath.isEmpty()) {
            try {
                File f = new File(scriptPath);
                if (f.exists() && f.canRead()) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    br.close();
                    currentScript = sb.toString();
                    Log.i(TAG, "[ScriptBridge] 加载本地脚本: " + scriptPath + " (" + currentScript.length() + "字节)");
                    return;
                } else {
                    Log.w(TAG, "[ScriptBridge] 脚本文件不存在或不可读: " + scriptPath);
                }
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 加载本地脚本失败", t);
            }
        }
        if (scriptUrl != null && !scriptUrl.isEmpty()) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(scriptUrl).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    br.close();
                    currentScript = sb.toString();
                    Log.i(TAG, "[ScriptBridge] 下载远程脚本: " + scriptUrl + " (" + currentScript.length() + "字节)");
                    return;
                }
                conn.disconnect();
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 下载远程脚本失败", t);
            }
        }
        Log.w(TAG, "[ScriptBridge] 未指定脚本来源，使用默认空脚本");
        currentScript = "";
    }

    // ================================================================
    // JS 引擎初始化 (使用反射兼容不同 Android 版本)
    // ================================================================

    private void initJsEngine() {
        try {
            Class<?> factoryCls = Class.forName("android.webkit.WebView");
            Log.i(TAG, "[ScriptBridge] 检测到 WebView 可用, 使用 WebView JS 引擎");
            initWebViewEngine();
        } catch (Throwable t) {
            Log.w(TAG, "[ScriptBridge] WebView 不可用, 使用本地规则回退");
        }
    }

    private volatile Object webView;
    private volatile Object webSettings;
    private volatile String jsResultBuffer;
    private volatile CountDownLatch jsLatch;

    private void initWebViewEngine() {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(appContext);
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(false);
            wv.getSettings().setLoadsImagesAutomatically(false);
            wv.getSettings().setBlockNetworkLoads(true);
            wv.setWebChromeClient(new android.webkit.WebChromeClient());
            wv.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    Log.d(TAG, "[ScriptBridge] WebView 页面加载完成");
                }
            });

            wv.evaluateJavascript(
                    "(function() {"
                            + "  window.orderData = null;"
                            + "  window.grabResult = null;"
                            + "  window.onOrder = function(json) {"
                            + "    try {"
                            + "      var order = JSON.parse(json);"
                            + "      var result = { shouldGrab: false, reason: '', order: order };"
                            + "      " + getDefaultJsLogic() + ""
                            + "      window.grabResult = JSON.stringify(result);"
                            + "    } catch(e) { window.grabResult = JSON.stringify({shouldGrab:false,reason:'JS error:'+e.message}); }"
                            + "  };"
                            + "  window.getConfig = function() {"
                            + "    return JSON.stringify(window.hookConfig || {});"
                            + "  };"
                            + "  window.setConfig = function(cfg) {"
                            + "    window.hookConfig = JSON.parse(cfg);"
                            + "  };"
                            + "})();",
                    null
            );

            if (currentScript != null && !currentScript.isEmpty()) {
                wv.evaluateJavascript(currentScript, null);
            }

            webView = wv;
            Log.i(TAG, "[ScriptBridge] WebView JS 引擎就绪");
        } catch (Throwable t) {
            Log.e(TAG, "[ScriptBridge] WebView 引擎初始化失败", t);
        }
    }

    private String getDefaultJsLogic() {
        return "var o = order;"
                + "var fee = o.deliveryFee || o.fee || o.income || 0;"
                + "var tip = o.tip || 0;"
                + "var total = parseFloat(fee) + parseFloat(tip);"
                + "var dist = o.deliveryDistance || o.distance || o.totalDistance || 0;"
                + "if (total >= 8 && dist <= 5000) { result.shouldGrab = true; result.reason = '金额达标+距离合适'; }"
                + "else if (total < 8) { result.reason = '金额不足:'+total; }"
                + "else if (dist > 5000) { result.reason = '距离过远:'+dist+'m'; }"
                + "else { result.reason = '未命中条件'; }";
    }

    // ================================================================
    // 接收订单 (供 Broadcaster 调用)
    // ================================================================

    public void onOrderCaptured(String tag, String url, String orderJson) {
        if (orderJson == null || orderJson.length() < 50) return;

        scriptHandler.post(() -> {
            long start = System.currentTimeMillis();
            try {
                JsResult result = processOrderInJs(orderJson);
                long elapsed = System.currentTimeMillis() - start;

                if (result.grab) {
                    Log.i(TAG, "[ScriptBridge] ★ JS判断抢单! 耗时=" + elapsed + "ms 原因=" + result.reason);
                    Broadcaster.getInstance().broadcastGrabOrder(orderJson);
                    Broadcaster.getInstance().grabOrderViaCallback(orderJson);
                    // === 手机端本地抢单 (无需电脑) ===
                    try {
                        GrabExecutor executor = GrabExecutor.getInstance();
                        if (executor.isEnabled()) {
                            String orderId = extractOrderId(orderJson);
                            executor.executeGrab(orderId);
                            Log.i(TAG, "[ScriptBridge] 本地抢单已触发: " + orderId);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "[ScriptBridge] 本地抢单触发异常", t);
                    }
                } else {
                    Log.d(TAG, "[ScriptBridge] JS判断不抢 耗时=" + elapsed + "ms 原因=" + result.reason);
                }

                if (processor != null) {
                    processor.onOrderProcessed(orderJson, result.grab);
                }
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 订单处理异常", t);
            }
        });
    }

    private static class JsResult {
        boolean grab;
        String reason;
        String orderJson;

        JsResult(boolean grab, String reason, String orderJson) {
            this.grab = grab;
            this.reason = reason;
            this.orderJson = orderJson;
        }
    }

    private JsResult processOrderInJs(String orderJson) {
        if (webView != null) {
            AtomicReference<JsResult> resultRef = new AtomicReference<>(
                    new JsResult(false, "JS 执行失败", orderJson));
            processViaWebView(orderJson, resultRef);
            JsResult result = resultRef.get();
            if (result != null && !"JS 执行失败".equals(result.reason)) {
                return result;
            }
        }
        // WebView 不可用或失败，回退到本地规则
        return localFallback(orderJson);
    }

    private void processViaWebView(String orderJson, AtomicReference<JsResult> resultRef) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            jsResultBuffer = null;
            jsLatch = latch;

            String escaped = orderJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            String callJs = "(function() {"
                    + "  try {"
                    + "    var resultStr = window.onOrder('" + escaped + "');"
                    + "    if (resultStr) {"
                    + "      window.JSBridgeCallback = resultStr;"
                    + "    }"
                    + "  } catch(e) { window.JSBridgeCallback = JSON.stringify({shouldGrab:false,reason:'eval error:'+e.message}); }"
                    + "})();";

            ((android.webkit.WebView) webView).evaluateJavascript(callJs, value -> {
                if (value != null && value.startsWith("\"")) {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
                }
                jsResultBuffer = value;
                latch.countDown();
            });

            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                Log.w(TAG, "[ScriptBridge] WebView JS 执行超时");
                return;
            }

            String raw = jsResultBuffer;
            if (raw != null && !raw.isEmpty()) {
                try {
                    JSONObject obj = new JSONObject(raw);
                    boolean grab = obj.optBoolean("shouldGrab", false);
                    String reason = obj.optString("reason", "");
                    String orderFromJs = obj.optString("order", orderJson);
                    resultRef.set(new JsResult(grab, reason, orderFromJs));
                } catch (Throwable t) {
                    Log.w(TAG, "[ScriptBridge] 解析 JS 返回值失败: " + raw);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[ScriptBridge] WebView 处理异常", t);
        }
    }

    private JsResult localFallback(String orderJson) {
        try {
            JSONObject order = new JSONObject(orderJson);
            double fee = order.optDouble("deliveryFee", order.optDouble("fee",
                    order.optDouble("income", order.optDouble("price", 0))));
            double tip = order.optDouble("tip", 0);
            double total = fee + tip;
            int dist = order.optInt("deliveryDistance", order.optInt("distance",
                    order.optInt("totalDistance", 0)));

            if (total >= 8 && dist <= 5000) {
                return new JsResult(true, "本地规则: 金额=" + total + " 距离=" + dist + "m", orderJson);
            }
            return new JsResult(false, "本地规则: 金额=" + total + " 距离=" + dist + "m", orderJson);
        } catch (Throwable t) {
            return new JsResult(false, "本地规则解析异常", orderJson);
        }
    }

    private String extractOrderId(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("waybillId")) return obj.getString("waybillId");
            if (obj.has("orderId")) return obj.getString("orderId");
            if (obj.has("data")) {
                JSONObject data = obj.getJSONObject("data");
                if (data.has("waybillId")) return data.getString("waybillId");
                if (data.has("orderId")) return data.getString("orderId");
            }
        } catch (Throwable t) {}
        return "unknown";
    }

    // ================================================================
    // 运行时脚本管理
    // ================================================================

    public void reloadScript() {
        initialized.set(false);
        scriptHandler.post(() -> {
            try {
                loadScript();
                if (webView != null) {
                    ((android.webkit.WebView) webView).evaluateJavascript(currentScript, null);
                }
                initialized.set(true);
                Log.i(TAG, "[ScriptBridge] ✓ 脚本已重新加载");
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 重新加载脚本失败", t);
            }
        });
    }

    public void executeRawScript(String script) {
        scriptHandler.post(() -> {
            try {
                if (webView != null) {
                    ((android.webkit.WebView) webView).evaluateJavascript(script, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "[ScriptBridge] 执行原始脚本失败", t);
            }
        });
    }

    public String getScriptInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("脚本来源: ");
        if (inlineScript != null && !inlineScript.isEmpty()) sb.append("内联 (").append(inlineScript.length()).append("字节)");
        else if (scriptPath != null && !scriptPath.isEmpty()) sb.append(scriptPath);
        else if (scriptUrl != null && !scriptUrl.isEmpty()) sb.append(scriptUrl);
        else sb.append("未指定");
        sb.append("\n当前脚本长度: ").append(currentScript != null ? currentScript.length() : 0).append("字节");
        sb.append("\nJS引擎: ").append(webView != null ? "WebView" : "本地规则回退");
        sb.append("\n超时: ").append(timeoutMs).append("ms");
        sb.append("\n已初始化: ").append(initialized.get());
        return sb.toString();
    }

    public void destroy() {
        try {
            if (webView != null) {
                ((android.webkit.WebView) webView).destroy();
                webView = null;
            }
            if (scriptThread != null) {
                scriptThread.quitSafely();
            }
        } catch (Throwable t) {
            Log.e(TAG, "[ScriptBridge] 销毁失败", t);
        }
    }
}