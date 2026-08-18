package com.zhongbao.orderhook;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 美团众包大厅滑动抢单执行器 (Shell 命令模式)
 * 
 * 抢单流程:
 *   1. 在大厅页面直接找到第一个订单的滑动按钮 Y 坐标
 *   2. 执行从左到右的滑动 (120 → 1036) 使用 input swipe 命令
 */
public class GrabExecutor {

    private static final String TAG = "ZBHOOK";
    private static volatile GrabExecutor INSTANCE;

    // 屏幕尺寸
    private int screenWidth = 1080;
    private int screenHeight = 2376;

    // 固定坐标 (根据用户提供的参数)
    private int slideStartX = 120;   // 滑动起点 X
    private int slideEndX = 1036;    // 滑动终点 X

    // 动态获取的 Y 坐标 (第一个订单的滑动按钮位置)
    private int grabButtonY = 0;

    // 冷却时间
    private int grabCooldown = 3000;
    private long lastGrabTime = 0;
    private boolean enabled = true;
    private boolean debugMode = true;

    // 滑动参数
    private int slideDuration = 400; // ms

    // 当前页面的 DecorView 缓存
    private View cachedDecorView;

    public static GrabExecutor getInstance() {
        if (INSTANCE == null) {
            synchronized (GrabExecutor.class) {
                if (INSTANCE == null) INSTANCE = new GrabExecutor();
            }
        }
        return INSTANCE;
    }

    private GrabExecutor() {
        loadConfig();
    }

    public void init(Object instr) {
        // 使用默认屏幕尺寸 (用户可在配置中修改)
        screenWidth = 1080;
        screenHeight = 2376;
        loadConfig();
        Log.i(TAG, "[GrabExecutor] ✓ 初始化完成 屏幕=" + screenWidth + "x" + screenHeight);
        Log.i(TAG, "[GrabExecutor] ✓ Shell 命令模式 input swipe 已就绪");
    }

    private void loadConfig() {
        try {
            android.content.Context ctx = MainHook.getAppContext();
            if (ctx == null) return;
            android.content.SharedPreferences sp = ctx.getSharedPreferences("zb_config", android.content.Context.MODE_PRIVATE);
            slideStartX = sp.getInt("slide_start_x", slideStartX);
            slideEndX = sp.getInt("slide_end_x", slideEndX);
            grabCooldown = sp.getInt("grab_cooldown", grabCooldown);
            slideDuration = sp.getInt("slide_duration", slideDuration);
            screenWidth = sp.getInt("screen_width", screenWidth);
            screenHeight = sp.getInt("screen_height", screenHeight);
            debugMode = sp.getBoolean("grab_debug", true);
            enabled = sp.getBoolean("grab_enabled", true);
        } catch (Throwable t) {
            Log.w(TAG, "[GrabExecutor] 加载配置失败", t);
        }
    }

    public void saveConfig(int startX, int endX, int cooldown, int duration, 
                           boolean debug, boolean enable, int width, int height) {
        this.slideStartX = startX;
        this.slideEndX = endX;
        this.grabCooldown = cooldown;
        this.slideDuration = duration;
        this.debugMode = debug;
        this.enabled = enable;
        this.screenWidth = width;
        this.screenHeight = height;
        try {
            android.content.Context ctx = MainHook.getAppContext();
            if (ctx != null) {
                android.content.SharedPreferences sp = ctx.getSharedPreferences("zb_config", android.content.Context.MODE_PRIVATE);
                sp.edit()
                        .putInt("slide_start_x", startX)
                        .putInt("slide_end_x", endX)
                        .putInt("grab_cooldown", cooldown)
                        .putInt("slide_duration", duration)
                        .putInt("screen_width", width)
                        .putInt("screen_height", height)
                        .putBoolean("grab_debug", debug)
                        .putBoolean("grab_enabled", enable)
                        .apply();
            }
        } catch (Throwable t) {}
    }

    public void setEnabled(boolean e) { this.enabled = e; }
    public boolean isEnabled() { return enabled; }
    public void setDebugMode(boolean d) { this.debugMode = d; }

    /**
     * 执行大厅滑动抢单 (Shell 命令模式)
     */
    public boolean executeGrab(String orderId) {
        if (!enabled) {
            Log.d(TAG, "[GrabExecutor] 已禁用");
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastGrabTime < grabCooldown) {
            Log.d(TAG, "[GrabExecutor] 冷却中 (" + (grabCooldown - (now - lastGrabTime)) + "ms)");
            return false;
        }

        lastGrabTime = now;
        Log.i(TAG, "[GrabExecutor] ★ 开始执行抢单 orderId=" + orderId);

        new Thread(() -> {
            try {
                // 步骤1: 查找第一个订单滑动按钮的 Y 坐标
                Log.i(TAG, "[GrabExecutor] 步骤1: 查找第一个订单按钮位置");
                grabButtonY = findFirstOrderButtonY();
                if (grabButtonY <= 0) {
                    Log.e(TAG, "[GrabExecutor] ❌ 未找到按钮, 使用兜底坐标");
                    grabButtonY = (int) (screenHeight * 0.35);
                }
                Log.i(TAG, "[GrabExecutor] ✓ 按钮 Y 坐标: " + grabButtonY);

                // 步骤2: 执行滑动抢单
                Log.i(TAG, "[GrabExecutor] 步骤2: 执行滑动命令 input swipe " + slideStartX + " " + grabButtonY + " " + slideEndX + " " + grabButtonY + " " + slideDuration);
                boolean success = executeShellSwipe(slideStartX, grabButtonY, slideEndX, grabButtonY, slideDuration);
                
                if (success) {
                    Log.i(TAG, "[GrabExecutor] ✓ 滑动命令执行成功");
                } else {
                    Log.e(TAG, "[GrabExecutor] ❌ 滑动命令执行失败");
                }
                sleep(200);

                Log.i(TAG, "[GrabExecutor] ★ 抢单执行完成");
            } catch (Throwable t) {
                Log.e(TAG, "[GrabExecutor] 抢单执行异常", t);
            }
        }, "zb-grab-exec").start();

        return true;
    }

    /**
     * 执行 Shell input swipe 命令
     */
    private boolean executeShellSwipe(int startX, int startY, int endX, int endY, int duration) {
        try {
            String cmd = String.format("input swipe %d %d %d %d %d",
                    startX, startY, endX, endY, duration);
            Log.d(TAG, "[GrabExecutor] 执行命令: " + cmd);
            
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int result = process.waitFor();
            
            // 读取输出
            java.io.InputStream is = process.getInputStream();
            byte[] buffer = new byte[1024];
            int n = is.read(buffer);
            String output = n > 0 ? new String(buffer, 0, n) : "";
            
            // 读取错误流
            java.io.InputStream es = process.getErrorStream();
            n = es.read(buffer);
            String error = n > 0 ? new String(buffer, 0, n) : "";
            
            if (result == 0) {
                Log.d(TAG, "[GrabExecutor] ✓ 命令执行成功 output=" + output.trim());
                return true;
            } else {
                Log.w(TAG, "[GrabExecutor] ✗ 命令执行失败 result=" + result + " error=" + error.trim());
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "[GrabExecutor] 执行 Shell 命令异常", t);
            return false;
        }
    }

    /**
     * 查找大厅页面第一个订单的滑动按钮 Y 坐标
     */
    private int findFirstOrderButtonY() {
        View decorView = getCurrentDecorView();
        if (decorView == null) {
            if (cachedDecorView != null) decorView = cachedDecorView;
        } else {
            cachedDecorView = decorView;
        }

        if (decorView == null) {
            Log.e(TAG, "[GrabExecutor] DecorView 为 null");
            return -1;
        }

        if (debugMode) {
            dumpViewTree(decorView, 0);
        }

        List<OrderButtonInfo> buttons = findAllOrderButtons(decorView);
        
        if (buttons.isEmpty()) {
            Log.w(TAG, "[GrabExecutor] 未找到任何订单按钮");
            return -1;
        }

        // 按 Y 坐标排序，取第一个（最靠上的）
        buttons.sort((a, b) -> Integer.compare(a.centerY, b.centerY));
        
        OrderButtonInfo first = buttons.get(0);
        Log.i(TAG, "[GrabExecutor] ★ 找到第一个按钮: " + first.text + " Y=" + first.centerY + " 类型=" + first.type);
        
        return first.centerY;
    }

    /**
     * 查找所有订单按钮
     */
    private List<OrderButtonInfo> findAllOrderButtons(View rootView) {
        List<OrderButtonInfo> result = new ArrayList<>();
        List<View> candidates = new ArrayList<>();
        
        collectButtonTextViews(rootView, candidates);
        
        if (debugMode) {
            Log.d(TAG, "[GrabExecutor] 共找到 " + candidates.size() + " 个候选 TextView");
        }

        for (View v : candidates) {
            if (v.getVisibility() != View.VISIBLE) continue;
            if (!(v instanceof TextView)) continue;
            
            TextView textView = (TextView) v;
            CharSequence text = textView.getText();
            if (text == null) continue;
            
            String s = text.toString();
            
            ButtonType type = getButtonType(s);
            if (type != null) {
                int[] location = new int[2];
                v.getLocationOnScreen(location);
                
                int y = location[1];
                int height = v.getHeight();
                int centerY = y + height / 2;
                
                result.add(new OrderButtonInfo(s, centerY, y, height, type));
                
                if (debugMode) {
                    Log.d(TAG, "[GrabExecutor]   按钮: '" + s + "' Y=" + centerY + " (top=" + y + ", h=" + height + ")");
                }
            }
        }

        return result;
    }

    /**
     * 递归收集包含抢单相关文字的 TextView
     */
    private void collectButtonTextViews(View view, List<View> list) {
        if (view == null) return;
        
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 2) {
                String s = text.toString();
                if (s.contains("抢单") || s.contains("转单") || s.contains("预约") || s.contains("抢预约")) {
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    // 只考虑屏幕内的 View
                    if (location[1] >= 0 && location[1] < screenHeight) {
                        list.add(view);
                    }
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectButtonTextViews(group.getChildAt(i), list);
            }
        }
    }

    private ButtonType getButtonType(String text) {
        if (text == null) return null;
        if (text.contains("抢单") || text.contains("抢预约")) return ButtonType.GRAB;
        if (text.contains("转单")) return ButtonType.TRANSFER;
        if (text.contains("预约")) return ButtonType.APPOINT;
        return null;
    }

    public enum ButtonType {
        GRAB("抢单"),
        TRANSFER("转单"),
        APPOINT("预约单");

        final String label;
        ButtonType(String label) { this.label = label; }
    }

    private static class OrderButtonInfo {
        String text;
        int centerY;
        int topY;
        int height;
        ButtonType type;

        OrderButtonInfo(String text, int centerY, int topY, int height, ButtonType type) {
            this.text = text;
            this.centerY = centerY;
            this.topY = topY;
            this.height = height;
            this.type = type;
        }
    }

    /**
     * 调试用: 输出 View 树结构
     */
    private void dumpViewTree(View view, int depth) {
        if (view == null || depth > 6) return;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        
        String className = view.getClass().getSimpleName();
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        
        sb.append(className)
          .append(" [").append(location[0]).append(",").append(location[1]).append("]")
          .append(" size=").append(view.getWidth()).append("x").append(view.getHeight())
          .append(" clickable=").append(view.isClickable());
        
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && text.length() < 30) {
                sb.append(" text='").append(text).append("'");
            }
        }
        
        Log.d(TAG, "[GrabExecutor] " + sb.toString());
        
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < Math.min(group.getChildCount(), 15); i++) {
                dumpViewTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    /**
     * 获取当前 Activity 的 DecorView
     */
    private View getCurrentDecorView() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            Object at = currentActivityThread.invoke(null);
            if (at != null) {
                Method getActivities = at.getClass().getDeclaredMethod("getActivityList");
                List<?> activities = (List<?>) getActivities.invoke(at);
                if (activities != null && !activities.isEmpty()) {
                    Object activity = activities.get(0);
                    Method getWindow = activity.getClass().getMethod("getWindow");
                    Object window = getWindow.invoke(activity);
                    if (window != null) {
                        Field decorField = window.getClass().getDeclaredField("mDecor");
                        decorField.setAccessible(true);
                        View decor = (View) decorField.get(window);
                        if (decor != null) {
                            Log.d(TAG, "[GrabExecutor] 获取 DecorView 成功");
                            return decor;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[GrabExecutor] 获取 DecorView 失败", t);
        }
        return null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    // ========== 配置 getter/setter ==========

    public String getConfigString() {
        return String.format("模式=Shell命令 屏幕=%dx%d 起点X=%d 终点X=%d Y=%d 耗时=%dms 调试=%s %s",
                screenWidth, screenHeight,
                slideStartX, slideEndX,
                grabButtonY,
                slideDuration,
                debugMode ? "开" : "关",
                enabled ? "启用" : "禁用");
    }

    public int getSlideStartX() { return slideStartX; }
    public int getSlideEndX() { return slideEndX; }
    public int getGrabButtonY() { return grabButtonY; }
    public int getCooldown() { return grabCooldown; }
    public int getSlideDuration() { return slideDuration; }
    public boolean isDebugMode() { return debugMode; }
}