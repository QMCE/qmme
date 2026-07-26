# Official vs qmme - 关键差异对比

## 一、QIMEI 机制

### 官方实现流程
```
1. WatchApplicationDelegate.getMSFInterfaceAdapter()
   └─> returns new WatchApplicationDelegate$getMSFInterfaceAdapter$1()
       └─> getBeaconAppKey() = "" (空字符串)
       └─> getWTUinStoreFileDirLastResort() = "/data/data/com.tencent.qqlite/files"

2. MSF (o.smali line 496):
   invoke-virtual {adapter}, getBeaconAppKey() → ""
   invoke-static {""}, QimeiSDK.getInstance("") → instance_A

3. Qqimei.b(false):
   QimeiSDK.getInstance("0AND05WGZE38P5II") → instance_B
   
4. Native libqimei.so 内部:
   - 可能有 DeviceId 缓存机制
   - instance_A 和 instance_B 底层可能指向同一 native 存储
```

### qmme 当前实现
```
1. QmmeApp.getMSFInterfaceAdapter()
   └─> returns new MSFInterfaceAdapter() {
       getBeaconAppKey() = "0AND05WGZE38P5II" ← 不一致！
   }

2. MSF (o.smali line 496):
   invoke-virtual {adapter}, getBeaconAppKey() → "0AND..."
   invoke-static {"0AND..."}, QimeiSDK.getInstance(...) → instance_B ✓

3. Qqimei.b(false):
   QimeiSDK.getInstance("0AND05WGZE38P5II") → instance_B ✓
```

**结论**: 我们的实现用了相同的 app key，所以应该得到同一个 instance_B。理论上没问题。

---

## 二、潜在问题点排查

### 1. MSF 进程的 `o.x` 静态字段初始化时机

**官方代码路径**:
```
MSF process starts → MsfCore.init() → ??? → o.a(o$d) 或 o.s()
```

**我们需要确认**:
- 在 qmme 中，MSF 进程何时被创建？
- MSF 连接建立时是否调用了 `o.a()` 或 `o.s()`？

**检查方法**:
```bash
adb logcat "QMME:V" "MSF.C.Util:D" | grep -E "initQimei|sput-object.*x:"
```

如果看不到相关日志，说明 MSF 可能没正确初始化其 QIMEI。

### 2. Native 启动顺序差异

**官方 WatchApplicationDelegate.onCreate()**:
```smali
invoke-super {p0}, Lmqq/app/MobileQQ;->onCreate()V
invoke-static {}, Landroid/os/Process;->myPid()I → pid
// ...检查是否为 com.tencent.qqwatch
if true: ManufacturerRouter.acquireWakeLock()
```

**我们的 QmmeApp.onCreate()**:
```kotlin
super.onCreate()
CrashCatcher.install(this)
SignatureProbe.dump(this)
initializeQmmkv()
initializeQimei()
PoWHelper.ensureLoaded()
BridgeBugly.init(this)
ConfigurationManager.init(this)
TelemetryBridge.init()
ensureRuntime(this)
```

**差异点**:
- 我们没有调用官方的 `NtStartupDirector` 的完整流程
- 这可能影响某些 native 模块的初始化时机

### 3. AppSetting.d (设备描述符)

**官方**:
```java
// 通过 AppSettingUtil.h() 获取
return AppSettingUtil.getInstance().h();
```

**qmme**:
```java
@Override
public String d(Context ignored) {
    return CLIENT_BUILD; // "9.0.7.2563"
}
```

这个应该是等效的。

### 4. GUID 获取方式

**官方**:
```kotlin
val guid = com.tencent.mobileqq.utils.KidInfoUtil.getGuid(context)
HexUtil.c(guid)  // hex encode
```

**qmme**:
```kotlin
override fun getCustomGuid(): ByteArray? = runCatching {
    val guid = com.tencent.mobileqq.utils.KidInfoUtil.getGuid(this)
    com.tencent.mobileqq.utils.HexUtil.c(guid)
}.getOrNull()
```

一致 ✅

---

## 三、必须进一步验证的点

### V1. 检查 MSF 进程是否正常运行
```bash
adb shell ps -A | grep msf
adb logcat -c
adb logcat "MSF:V" > /tmp/msf.log &
# 等待登录完成后
kill %1
grep "initQimei" /tmp/msf.log
```

### V2. 检查 SSO 包中的 QIMEI 字段
使用 tcpdump/mitmproxy 抓包，检查 SSO 包的 ReserveFields.qimei 字段是否有值。

### V3. 对比签名命令列表
检查 FEKit 的命令白名单是否与官方一致：
```bash
adb shell dumpsys package rj.qmme | grep -i feature
```

### V4. 检查 D2Key/A2 注入
查看 WtLogin 是否正确注入了票据。

---

## 四、建议的下一步行动

### Step 1: 立即执行
```bash
# 1. 查看当前版本运行日志
adb logcat -c
adb logcat -v threadtime \
    "QMME:V" "QMME-FORCED:V" "QMME-Bugly:V" \
    "MSF.C.Util:V" \
    "Qtlogin:V" "WtLogin:V" \
    > work/debug-runs/latest-build-$(date +%Y%m%d).log

# 2. 关键日志提取
grep "QIMEI init done" work/debug-runs/*.log | grep -v "=0"
grep "privacy policy state" work/debug-runs/*.log
grep "initQimei" work/debug-runs/*.log | tail -20
grep "sput-object.*o;->x" work/debug-runs/*.log || echo "No O.X sput found"
```

### Step 2: 如果仍有问题
- **方案 A**: 将 `getBeaconAppKey()` 改回返回 `""`（完全对齐官方）
- **方案 B**: 手动调用 `o.a()` 初始化 MSF 的 QIMEI 缓存
- **方案 C**: 检查是否有其他 SDK 初始化失败导致静默降级

### Step 3: 长期方案
- 引入完整的 ColdStartupTask 流程
- 添加完整的日志记录（类似官方 QLog 级别）
- 对比官方客户端的运行时行为

---

**更新时间**: 2026-07-26  
**优先级**: Step 1 → 等待输出 → Step 2
