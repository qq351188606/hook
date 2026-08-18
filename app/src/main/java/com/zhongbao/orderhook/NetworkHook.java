package com.zhongbao.orderhook;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * ============================================================
 * 订单网络层 Hook 核心实现
 * ============================================================
 * 两级 Hook 策略：
 *  1) 优先：Hook com.sankuai.meituan.retrofit2.Response.success()
 *     → 美团所有业务的统一响应入口
 *  2) 兜底：Hook okhttp3.internal.http.RealResponseBody.string()
 *     → 所有 HTTP 响应的最终出口
 * ============================================================
 */
public class NetworkHook {

    // ======= 订单相关 URL 关键词 =======
    private static final List<String> URL_KEYWORDS = Arrays.asList(
            "waybill",       // 运单（美团最常用的订单术语）
            "order",         // 订单
            "dispatch",      // 调度中心
            "grab",          // 抢单
            "assign",        // 派单
            "task",          // 任务
            "hall",          // 大厅
            "pool",          // 订单池
            "addition",      // 加单
            "neworder",      // 新订单
            "delivery",      // 配送
            "list",          // 列表
            "detail"         // 详情
    );

    // ======= 订单 JSON 特征字段（至少命中 3 个才算订单） =======
    private static final List<String> FIELD_KEYWORDS = Arrays.asList(
            "waybillId", "waybill_id",
            "orderId", "order_id", "orderNo",
            "poiName", "shopName", "poi_name", "merchantName",
            "poiAddress", "pickupAddress", "shop_address",
            "receiver", "customerName", "receiverName",
            "deliveryAddress", "address", "customer_address",
            "deliveryFee", "price", "fee", "amount", "income",
            "tip", "gratuity", "subsidy", "allowance",
            "distance", "deliveryDistance",
            "poiLat", "poiLng", "receiverLat", "receiverLng", "latitude", "longitude",
            "expireTime", "expectedTime", "createTime",
            "goods", "goodsName", "goodsInfo"
    );

    private static int orderCount = 0;

    // ============================================================
    // Hook 美团 Retrofit Response 层
    // ============================================================
    public static boolean hookMeituanRetrofit(ClassLoader cl) {
        try {
            Class<?> ResponseCls = XposedHelpers.findClassIfExists(
                    "com.sankuai.meituan.retrofit2.Response", cl);
            if (ResponseCls == null) {
                MainHook.logW("Retrofit.Response 类尚未加载（等待下一轮重试）");
                return false;
            }

            Class<?> RawResponseCls = XposedHelpers.findClassIfExists(
                    "com.sankuai.meituan.retrofit2.raw.RawResponse", cl);
            if (RawResponseCls == null) {
                MainHook.logW("Retrofit.RawResponse 类尚未加载");
                return false;
            }

            // 寻找 success(T, RawResponse) 方法
            Method successMethod = null;
            for (Method m : ResponseCls.getDeclaredMethods()) {
                if ("success".equals(m.getName()) && m.getParameterCount() == 2) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts[1].getName().contains("RawResponse")) {
                        successMethod = m;
                        break;
                    }
                }
            }
            if (successMethod == null) {
                MainHook.logW("未找到 Response.success(T, RawResponse) 方法");
                return false;
            }

            // 使用 XC_MethodHook 精确 Hook 到找到的方法（签名动态匹配）
            final Class<?> finalRawResponseCls = RawResponseCls;
            XposedHelpers.findAndHookMethod(
                    ResponseCls.getName(),
                    cl,
                    "success",
                    Object.class,
                    finalRawResponseCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object parsedBody = param.args[0];
                            Object rawResponse = param.args[1];
                            handleMeituanResponse(parsedBody, rawResponse);
                        }
                    }
            );

            MainHook.logI("[√] Hook 成功: 美团 Retrofit Response.success(Object, RawResponse)");
            return true;
        } catch (Throwable t) {
            MainHook.logE("Hook 美团 Retrofit 异常", t);
            return false;
        }
    }

    private static void handleMeituanResponse(Object parsedBody, Object rawResponse) {
        try {
            // --- 读取 URL ---
            String url = "";
            try {
                Object urlObj = XposedHelpers.callMethod(rawResponse, "url");
                if (urlObj != null) url = urlObj.toString();
            } catch (Throwable ignore) {}

            // --- 快速判断是否订单相关 ---
            boolean urlHit = isOrderUrl(url);
            String jsonStr = null;

            // --- 策略A: 用 parsedBody 转 JSON ---
            if (parsedBody != null && (urlHit || shouldTryCandidate(parsedBody))) {
                jsonStr = safeToJson(parsedBody);
                if (jsonStr != null && !isOrderJson(jsonStr)) {
                    // 解析后的对象可能已经是业务对象，字段名被混淆的话可能检测不到
                    // 先暂存，后面结合原始响应判断
                } else if (jsonStr != null && isOrderJson(jsonStr)) {
                    onOrderCaptured("retrofit_parsed_" + urlKeyword(url), url, jsonStr);
                    return;
                }
            }

            // --- 策略B: 读取原始 ResponseBody 字节（最可靠！） ---
            try {
                Object responseBody = XposedHelpers.callMethod(rawResponse, "body");
                if (responseBody != null) {
                    byte[] bytes = (byte[]) XposedHelpers.callMethod(responseBody, "bytes");
                    if (bytes != null && bytes.length > 50 && bytes.length < 2000000) {
                        String rawJson = new String(bytes, "UTF-8");
                        if (urlHit || isOrderJson(rawJson)) {
                            onOrderCaptured("retrofit_raw_" + urlKeyword(url), url, rawJson);
                            return;
                        }
                    }
                }
            } catch (Throwable t) {
                // 原始响应读取失败（比如流被消费了），但如果 parsedBody 已经命中也算
                if (jsonStr != null && isOrderJson(jsonStr)) {
                    onOrderCaptured("retrofit_parsed_fallback_" + urlKeyword(url), url, jsonStr);
                }
            }
        } catch (Throwable t) {
            MainHook.logE("handleMeituanResponse 异常", t);
        }
    }

    // ============================================================
    // 兜底 Hook OkHttp RealResponseBody.string()
    // ============================================================
    public static boolean hookOkHttpResponseBody(ClassLoader cl) {
        try {
            Class<?> RealResponseBody = XposedHelpers.findClassIfExists(
                    "okhttp3.internal.http.RealResponseBody", cl);
            if (RealResponseBody == null) {
                // 尝试另一个常见类名
                RealResponseBody = XposedHelpers.findClassIfExists(
                        "okhttp3.ResponseBody", cl);
                if (RealResponseBody == null) {
                    MainHook.logW("OkHttp ResponseBody 类尚未加载（正常，下一轮重试）");
                    return false;
                }
            }

            XposedHelpers.findAndHookMethod(RealResponseBody, "string",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String result = (String) param.getResult();
                            if (result == null) return;
                            int len = result.length();
                            // 过滤：长度在 100 ~ 2,000,000 字节（排除健康检查/小资源）
                            if (len < 100 || len > 2000000) return;
                            if (isOrderJson(result)) {
                                onOrderCaptured("okhttp_body", "", result);
                            }
                        }
                    }
            );

            // 额外 Hook bytes() 方法，防止 string() 没被调用的场景
            try {
                XposedHelpers.findAndHookMethod(RealResponseBody, "bytes",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                byte[] bytes = (byte[]) param.getResult();
                                if (bytes == null || bytes.length < 100 || bytes.length > 2000000) return;
                                String s = new String(bytes, "UTF-8");
                                if (isOrderJson(s)) {
                                    onOrderCaptured("okhttp_bytes", "", s);
                                }
                            }
                        }
                );
            } catch (Throwable ignore) { /* bytes() 不一定在这个类 */ }

            MainHook.logI("[√] Hook 成功: OkHttp ResponseBody.string() + bytes() [兜底层]");
            return true;
        } catch (Throwable t) {
            MainHook.logE("Hook OkHttp 异常", t);
            return false;
        }
    }

    // ============================================================
    // 订单捕获统一回调（日志 + 保存）
    // ============================================================
    private static void onOrderCaptured(String tag, String url, String json) {
        orderCount++;
        MainHook.logI("═══════════════════════════════════════════════");
        MainHook.logI("【★捕获订单响应★】#" + orderCount + " 来源=" + tag);
        if (!url.isEmpty()) MainHook.logI("  URL: " + url);
        MainHook.logI("  JSON 长度: " + json.length() + " 字节");
        MainHook.logI("  美化预览: " + prettyPrint(json));
        MainHook.logI("═══════════════════════════════════════════════");
        // 保存
        MainHook.saveOrderJson(tag.isEmpty() ? "order" : tag, json);
    }

    // ============================================================
    // 辅助判断函数
    // ============================================================

    private static boolean isOrderUrl(String url) {
        if (url == null || url.length() < 6) return false;
        String u = url.toLowerCase();
        for (String kw : URL_KEYWORDS) {
            if (u.contains(kw)) return true;
        }
        return false;
    }

    private static String urlKeyword(String url) {
        if (url == null) return "nourl";
        String u = url.toLowerCase();
        for (String kw : URL_KEYWORDS) {
            if (u.contains(kw)) return kw;
        }
        // 取 URL 末尾路径段作为 tag
        int idx = u.lastIndexOf('/');
        if (idx >= 0 && idx < u.length() - 1) {
            String tail = u.substring(idx + 1);
            if (tail.length() > 2 && tail.length() < 30) return tail;
        }
        return "unknown";
    }

    private static boolean shouldTryCandidate(Object parsedBody) {
        if (parsedBody == null) return false;
        String cn = parsedBody.getClass().getName().toLowerCase();
        // 类名包含 bean/response/model/waybill/order 就尝试转 JSON
        return cn.contains("bean") || cn.contains("response") || cn.contains("model")
                || cn.contains("waybill") || cn.contains("order") || cn.contains("result")
                || cn.contains("data") || cn.contains("list");
    }

    private static boolean isOrderJson(String json) {
        if (json == null || json.length() < 200) return false;
        int hit = 0;
        for (String f : FIELD_KEYWORDS) {
            if (json.contains(f)) {
                hit++;
                if (hit >= 3) return true;
            }
        }
        return false;
    }

    // ============================================================
    // 对象转 JSON（FastJSON 优先 → Gson 兜底 → toString 回退）
    // ============================================================
    private static String safeToJson(Object obj) {
        if (obj == null) return null;
        try {
            // 1) FastJSON
            try {
                Class<?> JSON = Class.forName("com.alibaba.fastjson.JSON");
                Method m = JSON.getMethod("toJSONString", Object.class);
                String r = (String) m.invoke(null, obj);
                if (r != null && r.length() > 0 && !"null".equals(r)) return r;
            } catch (Throwable t1) { /* 无 FastJSON */ }
            // 2) FastJSON2
            try {
                Class<?> JSON2 = Class.forName("com.alibaba.fastjson2.JSON");
                Method m = JSON2.getMethod("toJSONString", Object.class);
                String r = (String) m.invoke(null, obj);
                if (r != null && r.length() > 0 && !"null".equals(r)) return r;
            } catch (Throwable t2) { /* 无 FastJSON2 */ }
            // 3) Gson
            try {
                Class<?> Gson = Class.forName("com.google.gson.Gson");
                Object gson = Gson.newInstance();
                Method m = Gson.getMethod("toJson", Object.class);
                String r = (String) m.invoke(gson, obj);
                if (r != null && r.length() > 0 && !"null".equals(r)) return r;
            } catch (Throwable t3) { /* 无 Gson */ }
            // 4) toString
            String s = obj.toString();
            if (s != null && s.startsWith("{") && s.length() > 50) return s;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================
    // 美化 JSON 输出（日志预览用，最多预览 2000 字符）
    // ============================================================
    private static String prettyPrint(String json) {
        try {
            String preview = (json.length() > 2000) ? json.substring(0, 2000) + "...(截断 总长" + json.length() + ")" : json;
            try {
                JSONObject jo = new JSONObject(preview);
                return jo.toString(2);
            } catch (Throwable t) {
                // 非标准 JSON，直接返回
                return preview.replace('\n', ' ');
            }
        } catch (Throwable t) {
            return "<格式化失败>";
        }
    }

    // 反射取字段（预留，适配未来 Bean 直接读）
    @SuppressWarnings("unused")
    private static Object getFieldSilent(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }
}