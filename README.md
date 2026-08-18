# 美团众包订单Hook LSPosed 模块使用说明

> 适用环境：**无 Root 手机 + LSPatch（Shizuku 管理模式）**
> 目标 APP：美团众包（`com.sankuai.meituan.dispatch.crowdsource`）
> 功能：**直接 Hook 网络响应层**，全自动捕获订单大厅、订单详情的原始 JSON，无需脱壳

---

## 一、项目文件结构

```
ZhongbaoLSPosedModule/
├── settings.gradle                      # Gradle 工程设置
├── build.gradle                         # 顶层 Gradle 配置
├── gradle.properties                    # Gradle 属性
└── app/
    ├── build.gradle                     # APP 级 Gradle（含 Xposed API 依赖）
    ├── proguard-rules.pro               # 混淆规则（保留 Hook 类）
    └── src/main/
        ├── AndroidManifest.xml          # LSPosed 识别关键：含 xposedmodule meta-data + scope
        ├── assets/
        │   └── xposed_init              # LSPosed 入口：内容=Hook类全名（一行）
        ├── java/com/zhongbao/orderhook/
        │   ├── MainHook.java            # LSPosed Hook 入口（多轮延迟重试+加固兼容）
        │   ├── NetworkHook.java         # 核心订单捕获：Hook Retrofit + OkHttp 两级
        │   └── MainActivity.java        # 模块激活检测界面 + 订单记录查看
        └── res/
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml
            │   └── themes.xml
            └── xml/
                └── xposed_scope.xml     # LSPosed 作用域：只 Hook 美团众包
```

---

## 二、编译 APK

### 方法A：Android Studio（推荐）
1. 用 Android Studio **打开项目根目录**
2. 等待 Gradle 同步完成（首次会下载 Xposed API 依赖）
3. 菜单 → Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 生成 APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

### 方法B：命令行
```bash
cd ZhongbaoLSPosedModule
gradlew.bat assembleDebug
```

---

## 三、部署：LSPatch + Shizuku（无 Root 用户）

### 前置准备
1. 安装 **Shizuku**：https://shizuku.rikka.app/zh-hans/download/
2. 安装 **LSPatch**：https://github.com/LSPosed/LSPatch/releases

---

### ✅ 操作步骤（管理模式 = 推荐）

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 安装 2 个 APK 到手机：本模块 APK + 美团众包 | 都正常安装即可 |
| 2 | 打开 LSPatch App | 首页显示「管理」「本地模式」两个选项卡 |
| 3 | 点击「管理」选项卡 → 右下角「+」按钮 | |
| 4 | 在应用列表中选择「美团众包」 | 搜索框搜「美团」可快速定位 |
| 5 | 勾选「管理模式（Shizuku）」→ 确定 | 管理模式 = Shizuku 授权打补丁，美团 APP 本体 APK 不被修改 |
| 6 | 进入美团众包补丁详情页 →「模块」→ 勾选「美团众包订单Hook」 | 列表里能看到我们的模块 |
| 7 | 点击「应用」或「保存」 | 首次操作 Shizuku 会弹窗问权限，点「允许」|
| 8 | 强制停止美团众包 → 重新打开 | 必须冷启动才能加载 Hook |
| 9 | 重新打开「美团众包订单Hook」APP 查看状态 | 显示绿色「激活 ✓」= 成功 |

---

### ❓ 管理模式 vs 本地模式

| 模式 | 原理 | 是否改美团APK | 更新美团版本后 | 推荐度 |
|------|------|---------------|---------------|--------|
| 管理模式（Shizuku） | Shizuku 调用系统 API 临时注入 Hook | 不改，美团还是官方签名 | 重新勾选模块即可 | ⭐⭐⭐⭐⭐ |
| 本地模式 | 把模块代码嵌入并重打包美团APK | 修改，签名会变 | 要重新打包 | ⭐⭐ |

---

## 四、验证模块是否正常工作

### 4.1 激活状态检查
- ✅ 绿色「● 模块状态：激活 ✓」→ Hook 已生效
- ❌ 红色「× 模块状态：未激活 ✗」→ 回到第三步重新操作

### 4.2 订单捕获验证
1. 打开美团众包 APP，进入订单大厅 / 点击一个订单进入详情
2. 回到本模块 APP → 点击「刷新状态 & 重新扫描订单」按钮
3. 查看「最近捕获的订单 JSON」区域
4. 也可以用文件管理器访问：`/sdcard/Android/data/com.sankuai.meituan.dispatch.crowdsource/files/orders/`

---

## 五、拿到的订单 JSON 里包含什么？

```json
{
  "code": 200,
  "data": {
    "waybillId": "WB20260818XXXXXXXX",
    "orderId": "012345678901234",
    "poiName": "麦当劳（XX广场店）",
    "poiAddress": "XX市XX区XX路123号1F",
    "poiLat": 31.2304,
    "poiLng": 121.4737,
    "receiverName": "王**",
    "receiverPhone": "138****8888",
    "deliveryAddress": "XX市XX区XX小区8号楼1单元501",
    "distance": 3800,
    "deliveryFee": 8.5,
    "tip": 2.0,
    "subsidy": 1.5,
    "totalIncome": 12.0,
    "createTime": 1760000000000,
    "expireTime": 1760000600000,
    "goodsList": [
      { "goodsId": 987, "goodsName": "巨无霸套餐", "quantity": 1, "price": 45 }
    ],
    "status": 1,
    "tags": ["午高峰", "优质单", "大额单", "顺路"]
  }
}
```

---

## 六、常见问题排查

### Q1：模块APP里一直显示「未激活」
1. 检查 `assets/xposed_init` 是否存在且内容为 `com.zhongbao.orderhook.MainHook`
2. 检查 `AndroidManifest.xml` 是否包含 `xposedmodule=true`、`xposedminversion=82`
3. 检查 LSPatch 里美团众包是否使用「管理模式」且勾选了本模块
4. 是否执行过「强制停止美团众包 → 重新打开」

### Q2：激活了但是没有订单 JSON
1. 启动美团后等待 1 分钟以上（mtguard 加固需要时间解密加载业务代码）
2. 用 Logcat 过滤 `ZBHOOK` 标签查看 Hook 日志：`adb logcat -s ZBHOOK:D`
3. 进入美团众包后，手动进大厅、刷新、点击几个订单

### Q3：LSPatch 提示「Shizuku 未运行」
- 打开 Shizuku App，按提示通过「无线调试」或「ADB 命令」启动服务

### Q4：保存的 JSON 在哪里？
- 优先路径：`/sdcard/Android/data/com.sankuai.meituan.dispatch.crowdsource/files/orders/`
- Fallback 路径：`/sdcard/zhongbao_order/`

### Q5：美团加固更新后 Hook 失效？
- 一般不需要升级模块，因为 Hook 的是 OkHttp/Retrofit 通用层
- 如果真的失效了，重新编译一个新版本，LSPatch 里重新勾选即可

---

## 七、技术实现要点

### 为什么不需要脱壳？
Hook 的是 Java 层的网络响应封装类，不管 mtguard 把业务代码怎么加密：
```
服务器 → OkHttp(读字节) → Retrofit(解析JSON) → 业务代码
             ↑                    ↑
        兜底层 Hook         优先层 Hook
```

### 多轮延迟重试策略（应对加固）
```
第1轮: 启动后 +8s   （壳还没加载）
第2轮: 启动后 +23s  （部分 dex 解密）
第3轮: 启动后 +48s  （大多数业务代码就绪）
第4轮: 启动后 +88s  （兜底）
第5轮: 启动后 +148s （最终兜底）
```

### 订单识别：URL + JSON 字段双保险
```
URL 命中关键词(waybill/order/grab/hall/dispatch...)
  OR
JSON 中 >=3 个字段命中 (waybillId+poiName+deliveryFee+distance ...)
  -> 判定为订单响应，保存 JSON
```

---

## 八、后续可扩展功能

1. **过滤推送**：只保存 >N 元 或 特定商家/地址 的订单
2. **自动转发**：把订单 JSON 通过 MQTT/WebSocket/HTTP POST 推送到你的服务器
3. **自动抢单**：基于抓到的订单参数，再 Hook 抢单 API 的请求方法
4. **悬浮窗实时播报**：用悬浮窗在屏幕上显示最新订单金额/距离