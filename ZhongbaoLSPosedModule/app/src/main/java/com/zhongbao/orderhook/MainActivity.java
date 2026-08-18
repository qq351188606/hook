package com.zhongbao.orderhook;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView infoText;
    private TextView recentOrdersText;
    private TextView configStatusText;
    private LinearLayout container;
    private SharedPreferences sp;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("zb_config", MODE_PRIVATE);

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(container);
        setContentView(sv);

        buildUI();
        refreshStatus();
    }

    private void buildUI() {
        // 1. 标题
        TextView title = new TextView(this);
        title.setText("美团众包订单Hook v3.0 (手机端独立版)");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, dp(8));
        container.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, dp(8));
        container.addView(statusText);

        infoText = new TextView(this);
        infoText.setTextSize(13);
        infoText.setPadding(0, 0, 0, dp(12));
        container.addView(infoText);

        // 2. 本地抢单配置 (核心功能 - 滑动抢单)
        TextView grabTitle = new TextView(this);
        grabTitle.setText("═══ 本地滑动抢单配置 ═══");
        grabTitle.setTextSize(15);
        grabTitle.setPadding(0, dp(8), 0, dp(4));
        container.addView(grabTitle);

        // 抢单开关
        Button grabSwitchBtn = new Button(this);
        grabSwitchBtn.setText("自动抢单: " + (sp.getBoolean("grab_enabled", true) ? "✓ 已启用" : "✗ 已禁用"));
        grabSwitchBtn.setOnClickListener(v -> {
            boolean enabled = !sp.getBoolean("grab_enabled", true);
            sp.edit().putBoolean("grab_enabled", enabled).apply();
            GrabExecutor.getInstance().setEnabled(enabled);
            grabSwitchBtn.setText("自动抢单: " + (enabled ? "✓ 已启用" : "✗ 已禁用"));
            Toast.makeText(this, enabled ? "已启用自动抢单" : "已禁用自动抢单", Toast.LENGTH_SHORT).show();
        });
        container.addView(grabSwitchBtn);

        // 调试模式开关
        Button debugSwitchBtn = new Button(this);
        boolean debugOn = sp.getBoolean("grab_debug", true);
        debugSwitchBtn.setText("调试日志: " + (debugOn ? "✓ 开启" : "✗ 关闭"));
        debugSwitchBtn.setOnClickListener(v -> {
            boolean d = !sp.getBoolean("grab_debug", true);
            sp.edit().putBoolean("grab_debug", d).apply();
            GrabExecutor.getInstance().setDebugMode(d);
            debugSwitchBtn.setText("调试日志: " + (d ? "✓ 开启" : "✗ 关闭"));
            Toast.makeText(this, d ? "调试日志已开启 (查看 Logcat: ZBHOOK)" : "调试日志已关闭", Toast.LENGTH_SHORT).show();
        });
        container.addView(debugSwitchBtn);

        // 滑动参数配置
        TextView slideLabel = new TextView(this);
        slideLabel.setText("═══ 滑动参数配置 (Shell命令模式) ═══\n使用 input swipe 命令模拟滑动\n滑动方向: 从左向右\n起点 X=120, 终点 X=1036 (屏幕像素)");
        slideLabel.setTextSize(12);
        slideLabel.setPadding(0, dp(4), 0, dp(4));
        container.addView(slideLabel);

        // 起点 X
        TextView startXLabel = new TextView(this);
        startXLabel.setText("滑动起点 X 坐标 (像素):");
        startXLabel.setTextSize(12);
        container.addView(startXLabel);

        EditText startXInput = new EditText(this);
        startXInput.setHint("120");
        startXInput.setText(String.valueOf(sp.getInt("slide_start_x", 120)));
        startXInput.setSingleLine(true);
        container.addView(startXInput);

        // 终点 X
        TextView endXLabel = new TextView(this);
        endXLabel.setText("滑动终点 X 坐标 (像素):");
        endXLabel.setTextSize(12);
        container.addView(endXLabel);

        EditText endXInput = new EditText(this);
        endXInput.setHint("1036");
        endXInput.setText(String.valueOf(sp.getInt("slide_end_x", 1036)));
        endXInput.setSingleLine(true);
        container.addView(endXInput);

        // 滑动耗时
        TextView durationLabel = new TextView(this);
        durationLabel.setText("滑动耗时 (毫秒, 建议 300-500):");
        durationLabel.setTextSize(12);
        container.addView(durationLabel);

        EditText durationInput = new EditText(this);
        durationInput.setHint("400");
        durationInput.setText(String.valueOf(sp.getInt("slide_duration", 400)));
        durationInput.setSingleLine(true);
        container.addView(durationInput);

        // 屏幕宽度
        TextView swLabel = new TextView(this);
        swLabel.setText("屏幕宽度 (像素):");
        swLabel.setTextSize(12);
        container.addView(swLabel);

        EditText swInput = new EditText(this);
        swInput.setHint("1080");
        swInput.setText(String.valueOf(sp.getInt("screen_width", 1080)));
        swInput.setSingleLine(true);
        container.addView(swInput);

        // 屏幕高度
        TextView shLabel = new TextView(this);
        shLabel.setText("屏幕高度 (像素):");
        shLabel.setTextSize(12);
        container.addView(shLabel);

        EditText shInput = new EditText(this);
        shInput.setHint("2376");
        shInput.setText(String.valueOf(sp.getInt("screen_height", 2376)));
        shInput.setSingleLine(true);
        container.addView(shInput);

        // 冷却时间
        TextView cdLabel = new TextView(this);
        cdLabel.setText("抢单冷却时间 (毫秒):");
        cdLabel.setTextSize(12);
        container.addView(cdLabel);

        EditText cdInput = new EditText(this);
        cdInput.setHint("3000");
        cdInput.setText(String.valueOf(sp.getInt("grab_cooldown", 3000)));
        cdInput.setSingleLine(true);
        container.addView(cdInput);

        // 保存配置按钮
        Button saveGrabBtn = new Button(this);
        saveGrabBtn.setText("保存滑动配置");
        saveGrabBtn.setOnClickListener(v -> {
            try {
                int sx = Integer.parseInt(startXInput.getText().toString());
                int ex = Integer.parseInt(endXInput.getText().toString());
                int dur = Integer.parseInt(durationInput.getText().toString());
                int cd = Integer.parseInt(cdInput.getText().toString());
                int sw = Integer.parseInt(swInput.getText().toString());
                int sh = Integer.parseInt(shInput.getText().toString());

                GrabExecutor.getInstance().saveConfig(sx, ex, cd, dur, debugOn, true, sw, sh);
                Toast.makeText(this, "滑动配置已保存", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "请输入有效的整数", Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(saveGrabBtn);

        // 测试抢单按钮
        Button testGrabBtn = new Button(this);
        testGrabBtn.setText("▶ 测试抢单 (滑动一次)");
        testGrabBtn.setOnClickListener(v -> {
            boolean ok = GrabExecutor.getInstance().executeGrab("test_manual");
            Toast.makeText(this, ok ? "测试抢单已执行 (请观察手机屏幕)" : "执行失败 (可能在冷却中)", Toast.LENGTH_SHORT).show();
        });
        container.addView(testGrabBtn);

        // 提示文字
        TextView tipText = new TextView(this);
        tipText.setText("\n提示: 点击测试后，请观察屏幕上的滑动位置\n如果位置不对，请查看 Logcat 中的 ZBHOOK 日志\n日志会输出找到的按钮 Y 坐标");
        tipText.setTextSize(11);
        tipText.setPadding(0, dp(4), 0, dp(4));
        container.addView(tipText);

        // 3. 脚本配置区 (可选, 用于高级自定义)
        TextView cfgTitle = new TextView(this);
        cfgTitle.setText("\n═══ JS脚本配置 (可选/高级) ═══");
        cfgTitle.setTextSize(15);
        cfgTitle.setPadding(0, dp(12), 0, dp(4));
        container.addView(cfgTitle);

        // 内联脚本
        TextView inlineLabel = new TextView(this);
        inlineLabel.setText("内联JS脚本 (可选, 留空使用默认规则):");
        inlineLabel.setTextSize(12);
        container.addView(inlineLabel);

        EditText inlineInput = new EditText(this);
        inlineInput.setHint("在此粘贴自定义JS脚本...");
        inlineInput.setText(sp.getString("inline_script", ""));
        inlineInput.setMinLines(3);
        container.addView(inlineInput);

        Button saveScriptBtn = new Button(this);
        saveScriptBtn.setText("保存并加载JS脚本");
        saveScriptBtn.setOnClickListener(v -> {
            String inline = inlineInput.getText().toString();
            sp.edit().putString("inline_script", inline).apply();

            if (Broadcaster.getInstance().getScriptBridge() == null) {
                ScriptBridge bridge = new ScriptBridge(this);
                bridge.init();
                Broadcaster.getInstance().setScriptBridge(bridge);
            }
            ScriptBridge bridge = Broadcaster.getInstance().getScriptBridge();
            if (bridge != null) {
                bridge.setInlineScript(inline);
                bridge.reloadScript();
                Toast.makeText(this, "JS脚本已加载", Toast.LENGTH_SHORT).show();
            }
            updateConfigStatus();
        });
        container.addView(saveScriptBtn);

        // 4. 刷新/权限按钮
        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态 & 重新扫描");
        refreshBtn.setOnClickListener(v -> refreshStatus());
        container.addView(refreshBtn);

        Button permBtn = new Button(this);
        permBtn.setText("申请存储权限");
        permBtn.setOnClickListener(v -> requestStoragePerm());
        container.addView(permBtn);

        // 5. 配置状态
        configStatusText = new TextView(this);
        configStatusText.setTextSize(12);
        configStatusText.setPadding(0, dp(8), 0, dp(8));
        container.addView(configStatusText);

        // 6. 最近订单
        TextView subtitle = new TextView(this);
        subtitle.setText("最近捕获的订单:");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(8), 0, dp(4));
        container.addView(subtitle);

        recentOrdersText = new TextView(this);
        recentOrdersText.setTextSize(12);
        container.addView(recentOrdersText);
    }

    private void refreshStatus() {
        if (MainHook.MODULE_ACTIVE) {
            statusText.setText("● 模块状态: 激活 ✓");
            statusText.setTextColor(0xFF2E7D32);
        } else {
            statusText.setText("× 模块状态: 未激活 ✗");
            statusText.setTextColor(0xFFC62828);
        }

        StringBuilder info = new StringBuilder();
        info.append("目标APP: ").append(MainHook.TARGET_PKG).append('\n');
        info.append("输出目录: /sdcard/zhongbao_order/\n");
        info.append("核心机制: Retrofit Response + OkHttp ResponseBody\n");
        info.append("\n★ 本版本特性 (无需电脑):\n");
        info.append("  1. 手机本地Hook, 独立运行\n");
        info.append("  2. 内嵌抢单执行器 (Shell命令模式)\n");
        info.append("  3. 内嵌JS脚本引擎 (自定义判断)\n");
        info.append("  4. 支持屏幕坐标自定义\n");
        info.append("\n使用说明:\n");
        info.append("  1. 启用'本地自动抢单'\n");
        info.append("  2. 根据屏幕调整坐标\n");
        info.append("  3. 点击'测试抢单'验证\n");
        info.append("  4. 打开美团众包 → 进大厅 → 自动抢单!");
        infoText.setText(info.toString());

        updateConfigStatus();
        scanRecentOrders();
    }

    private void updateConfigStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 系统状态 ═══\n");
        GrabExecutor grabExec = GrabExecutor.getInstance();
        sb.append("本地抢单: ").append(grabExec.isEnabled() ? "✓ 启用" : "✗ 禁用").append('\n');
        sb.append("抢单配置: ").append(grabExec.getConfigString()).append('\n');

        Broadcaster bc = Broadcaster.getInstance();
        sb.append("文件推送: ").append(bc.isFileEnabled() ? "ON" : "OFF").append('\n');
        sb.append("队列待处理: ").append(bc.getQueueSize()).append('\n');

        ScriptBridge bridge = bc.getScriptBridge();
        if (bridge != null) {
            sb.append("JS Bridge: ON\n");
            sb.append(bridge.getScriptInfo()).append('\n');
        } else {
            sb.append("JS Bridge: OFF (使用默认规则)\n");
        }

        configStatusText.setText(sb.toString());
    }

    private void scanRecentOrders() {
        List<File> files = new ArrayList<>();
        String[] dirs = {
                "/sdcard/Android/data/" + MainHook.TARGET_PKG + "/files/orders",
                "/sdcard/zhongbao_order",
                "/sdcard/zhongbao_order/pushed"
        };
        for (String d : dirs) {
            File dir = new File(d);
            if (dir.exists() && dir.isDirectory()) {
                File[] arr = dir.listFiles();
                if (arr != null) {
                    for (File f : arr) {
                        if (f.getName().endsWith(".json") && f.length() > 50) {
                            files.add(f);
                        }
                    }
                }
            }
        }

        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        StringBuilder sb = new StringBuilder();
        int show = Math.min(files.size(), 10);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

        if (show == 0) {
            sb.append("（暂无订单文件\n启动美团众包并进入大厅后自动捕获）\n");
        } else {
            sb.append("共 ").append(files.size()).append(" 个订单文件，最新 ").append(show).append(" 个:\n\n");
            for (int i = 0; i < show; i++) {
                File f = files.get(i);
                sb.append("【").append(i + 1).append("】 ").append(sdf.format(new Date(f.lastModified()))).append('\n');
                sb.append("  名称: ").append(f.getName()).append('\n');
                sb.append("  大小: ").append(f.length()).append(" 字节\n");
                sb.append("  预览: ").append(readPreview(f, 300)).append("\n\n");
            }
        }
        recentOrdersText.setText(sb.toString());
    }

    private String readPreview(File f, int max) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            char[] buf = new char[max];
            int n = br.read(buf);
            br.close();
            String s = n > 0 ? new String(buf, 0, n) : "";
            String flat = s.replace('\n', ' ').replace('\r', ' ');
            return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
        } catch (IOException e) {
            return "<读取失败>";
        }
    }

    private void requestStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable t) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            }
        }
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}