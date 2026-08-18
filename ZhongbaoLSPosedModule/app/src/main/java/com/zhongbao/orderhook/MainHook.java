package com.zhongbao.orderhook;

/**
 * ============================================================
 * 美团众包订单Hook - LSPosed 模块入口
 * ============================================================
 * 关键设计：
 *  1. 兼容 LSPatch 非 Root 环境 + mtguard 加固延迟加载
 *  2. 采用"多轮延迟重试"策略，每轮间隔递增，最多重试 5 轮
 *  3. 优先 Hook 网络响应层（成功率 > 95%），而非硬编码 Activity
 *  4. 所有订单 JSON 自动落盘到 /sdcard/Android/data/com.sankuai.meituan.dispatch.crowdsource/files/orders/
 *     （LSPatch 嵌入模式下，写该目录不需要额外存储权限）
 * ============================================================
 */

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "ZBHOOK";
    public static final String TARGET_PKG = "com.sankuai.meituan.dispatch.crowdsource";

    // 模块激活标志（MainActivity 读取此变量判断模块是否生效）
    public static boolean MODULE_ACTIVE = false;

    // 重试配置（针对加固延迟加载）
    private static final int MAX_RETRY = 5;
    private static final long[] RETRY_DELAYS = {8000, 15000, 25000, 40000, 60000}; // 单位ms

    private static int currentRetry = 0;
    private static boolean retrofitHooked = false;
    private static boolean okhttpHooked = false;
    private static boolean classLoaderReady = false;

    private static ClassLoader appClassLoader;
    private static String outputDir;
    private static android.content.Context appContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 严格限定：只 Hook 目标进程，避免误伤其他进程
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        MODULE_ACTIVE = true;
        appClassLoader = lpparam.classLoader;
        classLoaderReady = true;

        // 获取 Application Context
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            Object at = currentActivityThread.invoke(null);
            if (at != null) {
                java.lang.reflect.Method getApplication = at.getClass().getDeclaredMethod("getApplication");
                Object app = getApplication.invoke(at);
                if (app instanceof android.app.Application) {
                    appContext = ((android.app.Application) app).getApplicationContext();
                    logI("获取到应用Context: " + (appContext != null));
                }
            }
        } catch (Throwable t) {
            logW("获取应用Context失败 (不影响核心Hook): " + t.getMessage());
        }

        // 输出目录优先放在目标APP的私有目录（LSPatch嵌入模式不需要存储权限）
        outputDir = "/sdcard/Android/data/" + TARGET_PKG + "/files/orders";
        ensureOutputDir();

        logI("========================================");
        logI("美团众包订单Hook模块 已加载（进程: " + lpparam.processName + "）");
        logI("========================================");

        // 初始化 Broadcaster（数据推送通道）
        try {
            Broadcaster.getInstance().init(appContext);
            logI("[Broadcaster] 数据推送通道已就绪");
        } catch (Throwable t) {
            logE("Broadcaster 初始化失败", t);
        }

        // 初始化 GrabExecutor (本地抢单执行器)
        try {
            GrabExecutor.getInstance().init(null);
            logI("[GrabExecutor] 本地抢单执行器已就绪");
        } catch (Throwable t) {
            logE("GrabExecutor 初始化失败", t);
        }

        // 第一轮：立即尝试 Hook（如果壳未加固/已加载则直接成功）
        scheduleRetryHook(0);
    }

    /**
     * 调度延迟重试 Hook
     */
    private void scheduleRetryHook(int retryIndex) {
        if (retryIndex >= MAX_RETRY) {
            logW("达到最大重试次数(" + MAX_RETRY + ")，停止调度");
            return;
        }

        long delay = RETRY_DELAYS[retryIndex];
        logI("【第 " + (retryIndex + 1) + "/" + MAX_RETRY + " 轮 Hook】将在 " + (delay / 1000) + " 秒后执行");

        new Thread(() -> {
            try {
                Thread.sleep(delay);
                currentRetry = retryIndex;
                doHookRound();
            } catch (Throwable t) {
                logE("延迟Hook线程异常", t);
            }
        }, "zbhook-retry-" + retryIndex).start();
    }

    /**
     * 执行一轮 Hook
     */
    private void doHookRound() {
        if (!classLoaderReady) {
            logW("ClassLoader 尚未就绪，跳过本轮");
            scheduleNextRetry();
            return;
        }

        int before = 0;
        int after = 0;

        // --- 1. Hook 美团 Retrofit Response 层 ---
        if (!retrofitHooked) {
            before++;
            boolean ok = NetworkHook.hookMeituanRetrofit(appClassLoader);
            if (ok) { retrofitHooked = true; after++; }
        }

        // --- 2. 兜底 Hook OkHttp RealResponseBody 层 ---
        if (!okhttpHooked) {
            before++;
            boolean ok = NetworkHook.hookOkHttpResponseBody(appClassLoader);
            if (ok) { okhttpHooked = true; after++; }
        }

        logI("本轮 Hook 结果：" + after + "/" + before + " 成功 | Retrofit=" + (retrofitHooked ? "✓" : "✗") + " OkHttp=" + (okhttpHooked ? "✓" : "✗"));

        if (retrofitHooked && okhttpHooked) {
            logI("全部 Hook 入口就位，结束重试");
            return;
        }

        // 未全部就位，安排下一轮
        scheduleNextRetry();
    }

    private void scheduleNextRetry() {
        int next = currentRetry + 1;
        if (next < MAX_RETRY) {
            scheduleRetryHook(next);
        } else {
            logW("全部重试已用完，最终状态：Retrofit=" + retrofitHooked + " OkHttp=" + okhttpHooked);
            logW("如果OkHttp已成功，则仍然可以捕获订单（OkHttp是兜底方案）");
        }
    }

    // ========== 工具方法 ==========

    private static void ensureOutputDir() {
        try {
            File dir = new File(outputDir);
            if (!dir.exists()) {
                boolean ok = dir.mkdirs();
                logI("输出目录 " + outputDir + " 创建:" + (ok ? "成功" : "失败"));
            }
            // 写入一个测试文件验证可写
            File test = new File(dir, ".hook_active_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()));
            test.createNewFile();
        } catch (Throwable t) {
            logE("输出目录准备失败", t);
            // 回退到 /sdcard/zhongbao_order（需要存储权限）
            outputDir = "/sdcard/zhongbao_order";
            try {
                new File(outputDir).mkdirs();
            } catch (Throwable ignore) {}
        }
    }

    public static void saveOrderJson(String tag, String json) {
        if (outputDir == null || json == null) return;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(new Date());
            String safeTag = (tag == null ? "order" : tag).replaceAll("[^a-zA-Z0-9_]", "_");
            if (safeTag.length() > 40) safeTag = safeTag.substring(0, 40);
            File f = new File(outputDir, ts + "_" + safeTag + ".json");
            FileWriter fw = new FileWriter(f);
            fw.write(json);
            fw.flush();
            fw.close();
            logI("【订单JSON已保存】" + f.getAbsolutePath() + " (" + json.length() + "字节)");
        } catch (IOException e) {
            logE("保存订单JSON失败", e);
        }
    }

    public static android.content.Context getAppContext() { return appContext; }

    public static void logI(String msg) {
        Log.i(TAG, msg);
    }

    public static void logW(String msg) {
        Log.w(TAG, msg);
    }

    public static void logE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}