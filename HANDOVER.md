# QMCE/QMME Native 逆向与 ntpro 重实现 — 接手文档

> 给下一位 harness 的交接说明。目标：继续对 QQNT 关键 native 库做逆向，
> 并在 `~/qmme/ntpro` 用 Kotlin/Android 重实现。原则：**只落已经由静态证据
> 证明的结论，不臆造参数/语义**。

## 0. 2026-09-05 运行时 jar 迁移（feat/migrate-qq-sdk）

运行时从裁剪版 `qq-core-watch-runtime.jar`（25 856 类）切换为 QMCE 同源的完整版
`qq-sdk.jar`（34 543 类，手表 QQ 9.0.7.2563，多出 qqlive/thumbplayer/richframework
等富媒体链路）。对 jar 做了两步 ASM 离线处理：

1. **包名重定向**（4 条精确映射，34 543/34 543 全量通过，0 残留）：
   `rj/qmce/lite/Flag` → `rj/qmme/Flag`；`rj/qmce/lite/fix/{KtFix,PendingIntentCompat,ResCompat}` → `rj/qmme/fix/*`。
2. **签名 patch 重打**（qmme 特有，qmce jar 没有）：5 个方法体重定向到
   `rj.qmme.fix.PkgSignFix` —— `oicq/wlogin_sdk/tools/util.{get_apk_id,get_apk_v,getPkgSigFromApkName}`、
   `com/tencent/mobileqq/msf/core/auth/c.a(PackageManager,int)` 与 `c.a(PackageManager,String[])`。

配套源码改动：

- `fix/KtFix.kt`：以 qmce 版为基准重写，并按 `work/ktfix-contract.txt`（153 条 jar
  调用签名，ScanHostCalls 扫描产出）补齐 47 个缺失桥接（`map`/`filter`/`zip`/`asSequence`/
  `averageOfInt` 等）；修正 `throwIndexOverflow` 为 void 返回（kotlinc 2.4 把 `Nothing`
  编译成 `Void`，与 jar 的 `()V` 调用不匹配）。**契约差集 = 0**（kotlinc 2.4.0 编译 +
  ContractCheck 核对）。
- 新增 `fix/ResCompat.kt`（官方 9.0.7 字符串表兜底，勿手改）与 `fix/PendingIntentCompat.kt`
  （S+ 强制 FLAG_IMMUTABLE，targetSdk 37 必需）。
- `Flag.kt` 首次真正生效：新 jar 的 `QLog.addLogItem` 读取 `DISABLE_QLOG_LOCAL_WRITE`。
- 构建：依赖切到 `qq-sdk.jar`；新增 `app/multidex-proguard.pro`（对照 qmce 改包名）；
  `keepRules/rules.keep` 补 `rj.qmme.Flag` / `rj.qmme.fix.**`；version 0.5.0(8)。

**回归验证清单（真机）**：登录（重点 WtLogin 签名链）→ 会话/收发消息 → 图片 → 群管理 →
设置页；`logcat` 过滤 `NoSuchMethod|NoClassDefFoundError|KtFix|PkgSignFix` 应无输出。
已知观察项：jar 引用 `com.bytedance.shadowhook` 但 qmme 走自研 BoostMultiDex stub，
预计不触发；APK 体积 +9 MB 左右。

## 1. 工作区

| 路径 | 作用 |
|---|---|
| `/home/rj/qmce-u` | 逆向研究源仓库（QMCE），`app-new/src/main/jniLibs/armeabi-v7a` 是全部 52 个 ARM32 `.so` |
| `/home/rj/qmce-u/work/dec-native-for-fix` | 已产出的逆向分析（qimei/qvm/per-lib） |
| `/home/rj/qmme` | 目标 Android 工程（QMME，非官方 QQ 客户端） |
| `/home/rj/qmme/ntpro` | Kotlin/Android 重实现模块（已纳入 QMME 构建） |

## 2. 已完成的逆向成果

### 2.1 全量 52 个 so 测绘

- 自动化管线：`work/dec-native-for-fix/analyze.py` + capstone/pyelftools
- 产出：`work/dec-native-for-fix/inventory.json`、每 so 的字符串分类 JSON、
  `JNI_OnLoad`/`.text` 反汇编、保护信号

### 2.2 QVM 审计（精确，非猜测）

- 全部 52 个 so 字节级扫描 `ir_vmp` / `interpreterImpl` / `QVM`
- **唯一命中：`libpoxy.so`**（`ir_vmp::Type`、`interpreterImpl`、
  `parseTypeData`、`getFfiTypeWithType`）
- `libwrapper.so` / `libgprowrapper.so` / `libfekit.so` 均不是 QVM
- 用户提示的 "anti-debug + 最高 11 层指针跳转" 尚未动态验证，文档在
  `ntpro/analysis/QVM_AUDIT.md`

### 2.3 libqimei.so（已完成，独立故障分析）

关键文件：`work/dec-native-for-fix/qimei/`

- `ANALYSIS.md`：Java→native 完整调用链
- `CGF_DECODED.md`：JNI_OnLoad CGF 状态机还原为伪 C
- `SIGNATURE_ANALYSIS.md` / `BYPASS_SOURCE_SIG.md`：签名/sourceDir 读取与 bypass
- `trace_qimei.js` / `trace_signature.js` / `trace_qimei_http.js` /
  `trace_native_signature.js` / `bypass_sourceDir_signature.js`：Frida 脚本
- 核心事实：
  - native 读 `PackageManager.getPackageInfo(pkg,64).signatures[0].toByteArray()`
    和 `ApplicationInfo.sourceDir`
  - `sub_2146c` 返回 0 会跳过 RegisterNatives，导致 qimei 静默为空
  - `ts-test.qq.com` 是测试服字符串，真实 host 需动态抓取

### 2.4 libwrapper.so / libgprowrapper.so（QQNT 核心）

- `libwrapper.so`：64 个 `com.tencent.qqnt.kernel.nativeinterface.*$CppProxy`
  类，1374 个 `Java_*` 导出；无 JNI_OnLoad；非 JNI 导出主要是 LiteTransfer
- `libgprowrapper.so`：5 个 `kernelgpro` 类，691 个导出
- 完整方法清单在 `ntpro/analysis/`：
  `libwrapper_jni_methods.json`、`libgprowrapper_jni_methods.json`、
  `libwrapper_nonjni_exports.txt`

### 2.5 libfekit.so（OLLVM 引擎）

- 无 `Java_*` 导出，Thumb `JNI_OnLoad(0x5353c)` + RegisterNatives 动态注册
- 有 `.datadiv_decode*` 符号 → OLLVM 字符串解密
- 明文 SSO command 已提取（`MsgProxy.SendMsg`、`trpc.QQService.*` 等）
- **静态拿不到 RegisterNatives 表**：类名/方法名运行时解密到栈上
- 详情：`ntpro/analysis/libfekit_NOTES.md`

## 3. ntpro 重实现现状

### 3.1 构建

```bash
cd /home/rj/qmme
./gradlew :ntpro:assembleDebug --no-daemon
./gradlew :ntpro:testDebugUnitTest --no-daemon
```

当前两项均 BUILD SUCCESSFUL，4 个测试类全部通过。

### 3.2 代码地图

| 文件 | 内容 |
|---|---|
| `ntpro/src/main/kotlin/rj/ntpro/kernel/model/NtModels.kt` | Kotlin 模型（Contact/MsgElement/MsgRecord/QueryMsgsParams/状态枚举等），字段来自 runtime jar javap |
| `kernel/KernelEngine.kt` | `IQQNTWrapperEngine` 生命周期重实现（init/readyToShow/destroy） |
| `kernel/KernelSession.kt` | `IQQNTWrapperSession` 会话/服务访问 |
| `kernel/KernelServices.kt` | 服务表面 + 内存实现：Msg/Recent/Group/Buddy + `SsoCommandRegistry` |
| `kernel/GlobalAdapter.kt` | `IGlobalAdapter` 回调面 |
| `kernel/transfer/LiteTransferSurface.kt` | `lt_*` C API 的 Kotlin 表面 |
| `ntpro/analysis/` | 研究依据（JNI inventory、QVM audit、fekit notes） |

### 3.3 已实现的服务方法（签名来自 runtime jar，行为是内存模拟）

- `NtInMemoryMsgService`：
  `getMsgsWithStatus / sendMsg / addKernelMsgListener /
  removeKernelMsgListener / recallMsg / deleteMsg / resendMsg / setMsgRead`
- `NtInMemoryRecentContactService`：`getRecentContactList / getRecentContactListSync`
- `NtInMemoryGroupService`：`getGroupList`
- `NtInMemoryBuddyService`：`getBuddyList / getBuddyListV2`
- `SsoCommandRegistry`：libfekit 静态 SSO command 路由表

## 4. 关键研究约束

1. **不无脑猜测**：新方法必须来自 JNI 导出清单或 runtime jar javap，
   否则只写 TODO，不发明语义
2. **QVM 需动态验证**：libpoxy 的 11 层指针跳转要 Frida trace
   `interpreterImpl` 确认，不能用字符串推断
3. **libfekit 的 RegisterNatives 表必须动态抓**：hook
   `JNIEnv->RegisterNatives`（vtable offset 0x35c）和
   `JNIEnv->FindClass`（offset 0x18）
4. **专有 jar 不可再分发**：`qq-core-watch-runtime.jar` 只用于 ABI 对照，
   ntpro 用独立 `rj.ntpro.kernel` 命名空间
5. **不要修改 `moye.*` / `rj.qmce.lite.fix` 等兼容代码**（QMCE 侧）

## 5. 本会话（2026-08）新增成果

### 5.1 libpoxy.so VM 脱壳（静态测绘 + Unicorn 动态突破）

**核心文档**：`ntpro/analysis/LIBPOXY_VM_STRUCTURE.md`（结构）
+ `ntpro/analysis/LIBPOXY_DYNAMIC.md`（**本会话动态进展，最新**）

- **整个 libpoxy 是 Thumb-2**；旧管线 `analyze.py` 用 `CS_MODE_ARM`
  反汇编是"静态拿不到"的首要原因（已更正）。
- **保护器 = 腾讯 QQSec 内部 UniverseCompiler**（IR-VM），RTTI 源路径
  `/Users/huacai/work/UniverseCompiler/...` 为证。非开源 ir_vmp。
- **`interpreterImpl` 入口 @ 0xfd830**：32KB 栈帧 + SIMD 保存 +
  7 个 key-schedule magic 常量（0x40b96c7c …），5 参布局与 RTTI 签名吻合。
- **字节码主循环 @ 0x16d218**：单字节操作码（0x30–0x4a）、`state+0xc`
  为 IP、跳转表分发（`ldr; add; mov pc`）。已还原语义与部分 handler
  （0x4c→0x16ddae 等）。

**本会话动态突破（Unicorn，更正了"必须设备 Frida"的旧结论）**：

- **Unicorn 受阻的真因是未应用 ELF 动态重定位**（非"Thumb-1 PUSH bug"，
  后者已被隔离实验证伪）。在映射镜像上应用 `.rel.dyn/.rel.plt`
  （RELATIVE/ABS32/GLOB_DAT/JUMP_SLOT ARM 语义）后，
  **`JNI_OnLoad` 全程仿真抓通**（6043 指令返回 JNI_VERSION_1_6）。
- **hook `RegisterNatives` 捕获真实 JNI 面**（HANDOVER #3 达成，无需设备）：
  libpoxy 仅注册 `com/tencent/secprotocol/ByteData` 两类方法——
  `getByte(Context,JJJJ,Object×4)[B` → fnPtr 0x12131；
  `putByte(Context,JJJJ,Object×4)I` → fnPtr 0x1f155。
  数据落 `ntpro/analysis/libpoxy_natives_dump.json`。
- **interpreterImpl 包装器 @0xfd780 参数契约还原**：分配 16 字节 `Value`、
  透传字节码指针、**返回码=ir_vmp 类型 tag**（0xa/0xb/0xc…）。
- **interpreterImpl 已可仿真进入**；其解密引擎是 **NEON 向量化白盒密码**
  （vld1.64×816 / vst1.64×758 / vorr/vand/vshl.u64/vext.64）。
- **VM 结构件清点**：94 个 opcode handler（均 bl 回 dispatcher 0x16d218）、
  类型辅助 0x16b734（239 调用）、操作数解码器 0x3a98c（325 调用）、
  232 个字节码程序指针（打包区字面池指向加密区 0x178000..0x17c694）。
- **唯一剩余障碍 = Unicorn 2.1.4 无 NEON**（A-profile 全 model VFP/NEON
  INSN_INVALID，CPACR no-op，本机无 qemu-arm）。脱壳只差一个 NEON 运行时。
- harness 就绪：`work/dec-native-for-fix/qvm/unicorn_poxy2.py`。

### 5.2 ntpro 重实现（已落代码，10 测试全过）

- **历史分页真实签名**：从 `qq-core-watch-runtime.jar` javap 提取
  `IKernelMsgService` 的 `getMsgs*` 16 个方法真实签名（`getMsgs` /
  `getMsgsIncludeSelf` / `getMsgsByMsgId` / `getMsgsBySeqAndCount` /
  `getMsgsByTypeFilter(s)` / `getMsgsExt` / `getMsgsWithStatus` /
  `getMsgsWithMsgTimeAndClientSeqForC2C`），对应 JNI 导出 `native_getMsgs*`。
  修正：原 `NtMsgService.jniMethods` 里的 `fetchMsgList`/`getMsgList` **是
  发明的名字**，runtime jar 与 JNI 清单均无，已替换。
- **新增模型**：`NtMsgsReq`、`NtMsgTypeFilter`、`NtMsgsRsp`（javap 字段
  对齐）；`NtMsgRecord` 增加 `msgSeq`/`msgTime`。
- **新增回调**：`NtMsgOperateCallback`（IMsgOperateCallback 镜像）、
  `NtMsgsRspOperateCallback`。
- **SsoCommandRegistry 扩充**：从 libfekit.so 明文全量提取 **97 条**
  SSO command（此前 15 条），含登录链路 ECDH 全套、FeedCloud/QChannel、
  qpay/qqhb 等。纯静态证据。
- **LiteTransferSurface 对齐**：按 `libwrapper_nonjni_exports.txt` 全部
  31 个 `lt_*` 导出重写（补 `enableFTNChannelOnly`/`enableNFCChannelOnly`/
  `enableRNFCChannelOnly`/`isEnableFTNChannel`/`isEnableFTNChannelOnly`/
  `generateSessionIdByType`/`hookGetTimeMillis` 等）。
- 新测试 `NtMsgPagingTest`（4 个分页断言）。

## 6. 下一步（按优先级）

1. **libpoxy 脱壳最后缺口 = interpreterImpl 的正确调用参数**（见
   LIBPOXY_DYNAMIC §9）。NEON 执行已用 qemu-arm 打通（不再需要 Unicorn）：
   harness `work/dec-native-for-fix/qvm/qemu/run_poxy.c`（裸机静态 ARM，
   `qemu-arm -s 0x10000000` 跑），`JNI_OnLoad`/`interpreterImpl`/NEON
   key-schedule 全部实测执行，GDB-RSP 追踪管线（`gdbrsp*.py`）已通。
   实测：9 个 dispatcher 在"字节码+空参数"下都不触发，跳转表惰性解密，
   getByte/putByte 假参数早退。**只剩**真实 App 输入下的字节码指针+类型化
   `Value*` 实参，属 App 侧秘密，离线无法合成。
2. 取该参数的两条路：
   - **设备 Frida**（推荐）：hook 0xfd830，在真实 App 调 getByte 时抓
     r1/r2/r3，并 dump dispatch 触发后明文化的跳转表 0x17e3fa → opcode→handler。
   - 把抓到的参数喂回本 qemu 管线离线复现（NEON 已就绪）。
   设备 Frida 另可用于 qimei native 签名确认、libfekit RegisterNatives。
3. ~~把 fetchMsgList 历史分页签名落进 ntpro~~ **已完成**（§5.2）
4. 按动态注册表实现 FEKit SSO command 路由/协议
5. 继续用 `analysis/libwrapper_nonjni_exports.txt` 细化 LiteTransfer 重实现

## 7. 已知风险

- QMME 根工程使用 AGP 9.3 / Kotlin 2.4 / 新版 DSL，
  `ntpro` 已适配但依赖网络下载 plugin
- `libwrapper/libgprowrapper` 体积大、符号多，静态语义还原有限
- qimei 故障的 server 侧校验（签名/sourceDir/包名）需要动态验证，
  静态 bypass 只是实验脚本
- **libpoxy 字节码/跳转表由 NEON 白盒密码惰性解密**；NEON 执行已用
  qemu-arm 解决（§5.1/LIBPOXY_DYNAMIC §7-8），opcode→handler 的最终对齐
  只缺真实 App 输入（设备 Frida 抓 getByte 实参）。旧"Thumb-1 PUSH bug /
  Unicorn 必须 / NEON 无解"等结论均已更正。
