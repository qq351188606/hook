package com.zhongbao.orderhook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * 订单筛选规则引擎
 * ============================================================
 * 功能: 根据自定义规则判断是否"抢"这个订单
 * 规则支持:
 *   - 最低金额 (deliveryFee + tip + subsidy)
 *   - 最大距离 (deliveryDistance)
 *   - 关键词包含 (商家名/地址关键词)
 *   - 排除关键词 (黑名单关键词)
 *   - 高峰期仅抢优质单
 *
 * 使用:
 *   OrderFilter filter = new OrderFilter.Builder()
 *       .minIncome(8.0)
 *       .maxDistance(5000)
 *       .includeKeyword("午餐")
 *       .excludeKeyword("偏远")
 *       .build();
 *   boolean shouldGrab = filter.shouldGrab(orderJson);
 * ============================================================
 */
public class OrderFilter {

    private double minIncome = 0;
    private double maxDistance = 50000;
    private List<String> includeKeywords = new ArrayList<>();
    private List<String> excludeKeywords = new ArrayList<>();
    private boolean onlyHighQuality = false;
    private boolean enabled = false;

    private OrderFilter() {}

    // ============================================================
    // Builder
    // ============================================================

    public static class Builder {
        private final OrderFilter f = new OrderFilter();

        public Builder minIncome(double min) { f.minIncome = min; f.enabled = true; return this; }
        public Builder maxDistance(int meters) { f.maxDistance = meters; f.enabled = true; return this; }
        public Builder includeKeyword(String kw) { f.includeKeywords.add(kw); f.enabled = true; return this; }
        public Builder excludeKeyword(String kw) { f.excludeKeywords.add(kw); f.enabled = true; return this; }
        public Builder onlyHighQuality(boolean v) { f.onlyHighQuality = v; f.enabled = true; return this; }

        public OrderFilter build() { return f; }
    }

    public static Builder newBuilder() { return new Builder(); }

    // ============================================================
    // 核心: 判断是否应该抢单
    // ============================================================

    public boolean shouldGrab(String json) {
        if (!enabled) return false;
        if (json == null || json.length() < 50) return false;

        try {
            // 解析 JSON (可能是 {"data":{...}} 或直接 {...})
            JSONObject order = extractOrderData(json);
            if (order == null) return false;

            // 1) 金额检查
            double income = extractIncome(order);
            if (income < minIncome) {
                MainHook.logI("[筛选] 跳过: 金额 " + income + " < 最低 " + minIncome);
                return false;
            }

            // 2) 距离检查
            int distance = extractDistance(order);
            if (distance > 0 && distance > maxDistance) {
                MainHook.logI("[筛选] 跳过: 距离 " + distance + "m > 最大 " + maxDistance + "m");
                return false;
            }

            // 3) 排除关键词 (黑名单)
            String allText = extractAllText(order).toLowerCase();
            for (String exc : excludeKeywords) {
                if (!exc.isEmpty() && allText.contains(exc.toLowerCase())) {
                    MainHook.logI("[筛选] 跳过: 包含排除关键词 '" + exc + "'");
                    return false;
                }
            }

            // 4) 包含关键词 (白名单)
            if (!includeKeywords.isEmpty()) {
                boolean hit = false;
                for (String inc : includeKeywords) {
                    if (!inc.isEmpty() && allText.contains(inc.toLowerCase())) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    MainHook.logI("[筛选] 跳过: 未包含任何白名单关键词");
                    return false;
                }
            }

            // 5) 优质单检查
            if (onlyHighQuality) {
                if (!isHighQuality(order)) {
                    MainHook.logI("[筛选] 跳过: 非优质单");
                    return false;
                }
            }

            // 全部通过
            MainHook.logI("[筛选] ✓ 命中! 金额=" + income + " 距离=" + distance + "m");
            return true;

        } catch (Throwable t) {
            MainHook.logE("筛选异常", t);
            return false;
        }
    }

    // ============================================================
    // JSON 解析辅助
    // ============================================================

    private JSONObject extractOrderData(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.has("data") && root.get("data") instanceof JSONObject) {
                return root.getJSONObject("data");
            }
            // 直接是订单对象
            if (root.has("waybillId") || root.has("orderId") || root.has("poiName")) {
                return root;
            }
            // 可能在列表里
            if (root.has("waybillList")) {
                JSONArray list = root.getJSONArray("waybillList");
                if (list.length() > 0) return list.getJSONObject(0);
            }
            if (root.has("list")) {
                JSONArray list = root.getJSONArray("list");
                if (list.length() > 0) return list.getJSONObject(0);
            }
            return root;
        } catch (Throwable t) {
            return null;
        }
    }

    private double extractIncome(JSONObject order) {
        try {
            // 优先 deliveryFee + tip + subsidy
            double fee = order.optDouble("deliveryFee", order.optDouble("fee", 0));
            double tip = order.optDouble("tip", 0);
            double subsidy = order.optDouble("subsidy", order.optDouble("allowance", 0));
            double total = fee + tip + subsidy;
            if (total > 0) return total;

            // 直接 income / price
            total = order.optDouble("income", order.optDouble("totalIncome", 0));
            if (total > 0) return total;

            total = order.optDouble("price", order.optDouble("amount", 0));
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    private int extractDistance(JSONObject order) {
        try {
            int d = order.optInt("deliveryDistance", order.optInt("distance", 0));
            if (d == 0) d = order.optInt("totalDistance", 0);
            if (d == 0) d = order.optInt("pickupDistance", 0) + order.optInt("deliveryDistance", 0);
            return d;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String extractAllText(JSONObject order) {
        StringBuilder sb = new StringBuilder();
        try {
            String[] textFields = {"poiName", "shopName", "poiAddress", "pickupAddress",
                    "deliveryAddress", "receiverName", "receiver",
                    "merchantName", "poi_name", "goodsName", "goods"};
            for (String f : textFields) {
                String v = order.optString(f, "");
                if (!v.isEmpty()) sb.append(v).append(" ");
            }
            // 标签
            JSONArray tags = order.optJSONArray("tags");
            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) sb.append(tags.getString(i)).append(" ");
            }
        } catch (Throwable t) {}
        return sb.toString();
    }

    private boolean isHighQuality(JSONObject order) {
        try {
            // 优质单特征: 大额单 + 有标签 + 距离近
            double income = extractIncome(order);
            int distance = extractDistance(order);
            JSONArray tags = order.optJSONArray("tags");
            if (income >= 10 && distance <= 3000) return true;
            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) {
                    String t = tags.getString(i);
                    if (t.contains("优质") || t.contains("大额") || t.contains("顺路")) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================
    // 静态默认规则 (可在代码里直接使用)
    // ============================================================
    public static OrderFilter getDefault() {
        return new Builder()
                .minIncome(8.0)
                .maxDistance(5000)
                .build();
    }
}