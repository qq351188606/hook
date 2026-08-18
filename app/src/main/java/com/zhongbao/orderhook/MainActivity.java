package com.zhongbao.orderhook;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模块激活检测 & 订单记录查看 Activity
 * （不需要 Hook 目标APP也能打开，用于确认模块是否正常工作）
 */
public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView infoText;
    private TextView recentOrdersText;
    private LinearLayout container;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 动态构建 UI，避免依赖 layout XML
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(container);
        setContentView(sv);

        // 1. 模块状态标题
        TextView title = new TextView(this);
        title.setText("美团众包订单Hook 模块");
        title.setTextSize(20);
        title.setPadding(0, 0, 0, pad);
        container.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, pad);
        container.addView(statusText);

        infoText = new TextView(this);
        infoText.setTextSize(13);
        infoText.setPadding(0, 0, 0, pad);
        container.addView(infoText);

        // 2. 刷新按钮
        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态 & 重新扫描订单");
        refreshBtn.setOnClickListener(v -> refreshStatus());
        container.addView(refreshBtn);

        // 3. 申请存储权限（仅用于读 /sdcard/zhongbao_order 目录，私有目录不需要）
        Button permBtn = new Button(this);
        permBtn.setText("申请存储权限（用于跨APP访问订单）");
        permBtn.setOnClickListener(v -> requestStoragePerm());
        container.addView(permBtn);

        // 4. 最近订单列表
        TextView subtitle = new TextView(this);
        subtitle.setText("\n最近捕获的订单 JSON:");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, pad, 0, pad / 2);
        container.addView(subtitle);

        recentOrdersText = new TextView(this);
        recentOrdersText.setTextSize(12);
        recentOrdersText.setPadding(0, 0, 0, pad);
        container.addView(recentOrdersText);

        // 首次刷新
        refreshStatus();
    }

    private void refreshStatus() {
        // --- 模块激活判断 ---
        if (MainHook.MODULE_ACTIVE) {
            statusText.setText("● 模块状态：激活 ✓");
            statusText.setTextColor(0xFF2E7D32); // 绿色
        } else {
            statusText.setText("× 模块状态：未激活 ✗\n" +
                    "\n请按下方步骤操作：\n" +
                    "1. 打开 LSPatch App\n" +
                    "2. 点击「管理」→「+」选择「美团众包」\n" +
                    "3. 选择「管理模式（Shizuku）」\n" +
                    "4. 在「补丁操作」→「模块」里勾选本模块\n" +
                    "5. 点击「应用补丁」并重新启动美团众包");
            statusText.setTextColor(0xFFC62828); // 红色
        }

        StringBuilder info = new StringBuilder();
        info.append("目标APP包名: ").append(MainHook.TARGET_PKG).append('\n');
        info.append("输出目录1: /sdcard/Android/data/").append(MainHook.TARGET_PKG).append("/files/orders/\n");
        info.append("输出目录2: /sdcard/zhongbao_order/\n");
        info.append('\n');
        info.append("Hook 机制:\n");
        info.append("  优先: com.sankuai.meituan.retrofit2.Response.success()\n");
        info.append("  兜底: okhttp3.ResponseBody.string()/bytes()\n");
        info.append('\n');
        info.append("订单识别规则:\n");
        info.append("  URL 命中 OR JSON 包含 3+ 订单字段 (waybillId/poiName/deliveryFee/distance...)");

        infoText.setText(info.toString());

        // 扫描订单文件
        scanRecentOrders();
    }

    private void scanRecentOrders() {
        List<File> files = new ArrayList<>();

        String[] dirs = {
                "/sdcard/Android/data/" + MainHook.TARGET_PKG + "/files/orders",
                "/sdcard/zhongbao_order"
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

        // 按修改时间倒序，取最新 10 个
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        StringBuilder sb = new StringBuilder();
        int show = Math.min(files.size(), 10);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

        if (show == 0) {
            sb.append("（暂无订单文件，启动美团众包并进入大厅/点击订单后再刷新）\n");
        } else {
            sb.append("共 ").append(files.size()).append(" 个订单文件，最新 ").append(show).append(" 个:\n\n");
            for (int i = 0; i < show; i++) {
                File f = files.get(i);
                sb.append("【").append(i + 1).append("】 ").append(sdf.format(new Date(f.lastModified()))).append('\n');
                sb.append("  名称: ").append(f.getName()).append('\n');
                sb.append("  大小: ").append(f.length()).append(" 字节\n");
                sb.append("  路径: ").append(f.getAbsolutePath()).append('\n');
                // 预览前 200 字符
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
            // 取 JSON 的前 200 字符单行
            String flat = s.replace('\n', ' ').replace('\r', ' ');
            if (flat.length() > 200) flat = flat.substring(0, 200) + "...";
            return flat;
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
                    Toast.makeText(this, "请授予所有文件访问权限后返回", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                Toast.makeText(this, "已拥有存储权限", Toast.LENGTH_SHORT).show();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            } else {
                Toast.makeText(this, "已拥有存储权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}