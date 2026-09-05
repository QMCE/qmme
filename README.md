# QMME

QMME 是一个**非官方**的 QQ 客户端项目：它内嵌 **手表 QQ 9.0.7.2563** 的框架 + NT 内核，并在其之上用一套自行构造的 UI 与包装重新驱动登录、会话与消息能力。

普通用户请加 QQ 群 （在简介） 下载。

---

## 免责声明

- 本项目与腾讯公司无任何隶属或授权关系，"QQ" 商标归腾讯所有。
- 项目依赖腾讯 QQ 手表版的专有运行时（`qq-core-watch-runtime.jar` 及 `jniLibs/` 下的 native `.so`）。这些专有组件**不可再分发**，仅用于个人学习与技术研究，请于下载 24 小时后删除。
- 请在遵守相关法律法规及腾讯服务条款的前提下使用，风险自负。

---

## 快速开始

最小可运行路径，细节见下文 [构建与运行](#构建与运行)。

**前置条件**

- JDK 17+
- Android SDK（含 `compileSdk 37` 对应 platform 与 build-tools，已接受 license）
- 支持 `armeabi-v7a` 的真机或模拟器

**首次运行**

```bash
# 1. 指向本地 Android SDK
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# 2. 编译并安装 Debug 包到已连接设备
./gradlew :app:installDebug

# 3. 启动应用
adb shell am start -n rj.qmme/.ui.MainActivity
```

---

## 功能特性

当前处于早期开发阶段，已实现：

- **登录 / 账号**：基于 WtLogin 的手表版登录、账号持久化；被顶号 / 过期 / 强制下线（expired、kicked、forceLogout、suspend 等）时自动清理状态并回到登录页。
- **会话与联系人**：最近会话列表、联系人列表、联系人资料页。
- **单聊消息**：加载最新消息、加载更早历史、实时收消息、发送文本、标记已读。
- **图片**：系统图片选择器发送（`PicElement` 富媒体）、图片消息展示、全屏预览、保存到 `Pictures/QMME`。
- **消息操作**：长按菜单支持回复、转发、多选、复制、撤回、删除、重发（危险操作二次确认）。
- **语音**：系统录音栈发送 / 官方播放器收听。
- **会话能力**：会话设置（置顶 / 免打扰）、群管理 Activity（全员禁言 / 公告 / 踢人）、群成员列表、本地消息搜索、输入草稿。
- **设置**：交互开关、退出确认、清除草稿、AI 服务端点配置、关于页。
- **通知**：消息通知与联系人/群系统通知（好友申请、群管理通知），通知中心页可处理申请；前台会话自动抑制当前聊天通知；通知点击直达对应会话。
- **AI 摘要**：聊天工具栏一键总结最近消息，OpenAI 兼容流式输出；内置 opencode zen 免费模型 `big-pickle`（匿名可用），也可在设置页自定义端点覆盖。
- **AI 助手（Fluoxetine）**：可调用 QQ 工具的对话式 Agent——发消息/撤回/已读、群管理（禁言/踢人/公告）、好友与群通知审批、置顶/免打扰、事件监听与定时提醒；写操作逐条批准，默认关闭，设置中开启。
- **其它**：表情资源桥接、APNG/Lottie 富图 native 初始化、崩溃捕获页、登录后进程重启以获得干净的内核初始化。

尚未完成：群聊 chatType/peerUid 映射的真机验证、QQ 空间、音视频通话，以及富媒体收发的服务端联调。

本轮相对 QMCE 手表端补齐的手机能力：

- **设置 / 关于 / 退出确认**：手机全屏设置页（回车发送、退出前确认、清草稿）、关于页、危险操作确认页。
- **联系人资料**：点击联系人进入资料页，再发起聊天。
- **会话设置**：置顶、免打扰；群聊可进群成员列表。
- **群管理**：独立 Activity，群聊右上角更多进入；全员禁言、发布公告、移出成员。
- **回复 / 转发 / 多选**：长按消息可回复、转发、进入多选批量删除/转发。
- **语音**：附件面板录音发送，气泡点击播放。
- **本地搜索 / 草稿**：会话内搜索已加载消息；输入草稿自动保存与恢复。
- **回复气泡展示**：消息气泡内显示被回复内容摘要。

---

## 技术栈

| 类别  | 选型                                                                                |
|-----|-----------------------------------------------------------------------------------|
| 语言  | Kotlin 2.4.0 + Java 17                                                            |
| 构建  | Android Gradle Plugin 9.3.0，Gradle 9.5.0                                          |
| UI  | Hikage 1.1.1（原生 View + Kotlin DSL）、BetterAndroid、Material Components、RecyclerView |
| 架构  | MVVM（AndroidX ViewModel + Lifecycle）、Kotlin Coroutines / StateFlow                |
| 底层  | `qq-sdk.jar`（手表 QQ 9.0.7.2563 完整提取，MSF + NT Kernel，`appId=537282233`）、MMKV/QMMKV    |
| ABI | 仅 `armeabi-v7a`；`minSdk 23`，`targetSdk / compileSdk 37`                           |

---

## 架构概览

应用把官方 MobileQQ 生命周期在 `QmmeApp` 中进行实现，再用协调器统一管理 runtime 身份。

```
QmmeApp (WatchApplicationDelegate)
  ├─ 初始化 QMMKV/MMKV、MultiDex、签名伪装、崩溃捕获
  ├─ 内嵌 MobileQQ 生命周期，绑定/解绑账号，处理官方下线回调
  └─ 选择性把包名伪装成 com.tencent.qqlite（仅供 WtLogin/签名相关调用）

runtime/   RuntimeCoordinator · RuntimeSession · RuntimeLifecycleState
           → runtime 身份与 generation 的唯一事实来源，拒绝陈旧缓存写入

kernel/    KernelBridge + ProjectKernelBootstrap/Dependencies/DeviceInfo
           → 按序加载 NT native 库链、启动 KernelService、缓存各内核服务、
             桥接 MSF 推送与前台状态

data/      ChatRepository（IKernelMsgService/kernelpublic.Contact 适配层）、
           LoginPrefs、OnlineStatus、MediaStoreSaver、EmotionAssetBridge、
           notify/（联系人/群系统通知仓库）、AiSettings、ai/（SSE 摘要客户端）

viewmodel/ AuthViewModel · ChatListViewModel · ChatDetailViewModel · ContactsViewModel ·
           NotificationCenterViewModel

ui/        MainActivity + ViewNavigator（基于 View 的导航栈，每页独立 LifecycleOwner）
           各 *Hikagable 页面（Login/Main/ChatDetail/ImagePreview/Crash）与 Adapter

fix/       兼容垫片：LegacyKiller（包名映射 PM 代理）、
           PackageSignatureProvider/PkgSignFix/SignatureProbe（IPC 签名伪装）、
           KtFix（Kotlin stdlib 桥，jar 内 1148 处调用）/ ResCompat（官方字符串资源兜底）/
           PendingIntentCompat（S+ 补 FLAG_IMMUTABLE）等
```

**运行时生命周期**：`COLD → ATTACHING → APPLICATION_READY → RUNTIME_CREATED → ACCOUNT_BOUND → KERNEL_STARTING → ONLINE`，由 `RuntimeCoordinator` 统一记录与保护。

### 关于运行时 jar

`app/libs/qq-sdk.jar` 是手表 QQ 9.0.7.2563 的完整提取（34543 个类，对照 QMCE 一致），
在此基础上做了两步离线处理（工具链见 `work/`，一次性产物，不进构建流程）：

1. **包名重定向**：ASM 把 jar 内 4 个宿主类引用整体改写到 `rj.qmme.*`
   （`Flag`、`fix/KtFix`、`fix/PendingIntentCompat`、`fix/ResCompat`），全量校验 0 残留。
2. **签名 patch 重打**：`oicq/wlogin_sdk/tools/util`（`get_apk_id` / `get_apk_v` /
   `getPkgSigFromApkName`）与 `com/tencent/mobileqq/msf/core/auth/c`（两个 `a(PackageManager,...)`）
   共 5 个方法体重定向到 `rj.qmme.fix.PkgSignFix`。

jar 调用的全部 153 条 `KtFix` 方法签名固化在 `work/ktfix-contract.txt`，
`fix/KtFix.kt` 覆盖并逐条核对（差集必须为 0）。

---

## 项目结构

```
qmme/
├─ app/
│  ├─ build.gradle.kts
│  ├─ libs/                 # runtime jar
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/rj/qmme/      # 应用代码（见上文架构）
│     ├─ jniLibs/armeabi-v7a/  # NT 内核 native .so
│     ├─ keepRules/         # ProGuard/R8 keep 规则
│     └─ res/
├─ gradle/libs.versions.toml
└─ settings.gradle.kts
```

---

## 构建与运行

### 前置要求

- JDK 17+
- Android SDK（含 `compileSdk 37` 对应的 platform 与 build-tools，并已接受相应 license）

### 配置 SDK 路径

在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/your/android-sdk
```

或导出环境变量：

```bash
export ANDROID_HOME=/path/to/your/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

### 常用命令

```bash
# 编译 Kotlin
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# 打 Debug
./gradlew :app:assembleDebug
```

> 构建配置说明：`gradle.properties` 中已禁用 Gradle daemon、开启 parallel 与 configuration-cache；因 Hikage 1.1.1 约束，`android.builtInKotlin=false` 且 `android.newDsl=false`。

---

## 已知限制

- 仅打包 `armeabi-v7a`，需在支持该 ABI 的设备 / 模拟器上运行。
- AI 功能默认走内置 opencode zen 免费端点（`big-pickle`，匿名可用）；自定义端点需三项齐全。
- 群聊 chatType/peerUid 映射的真机验证、QQ 空间、音视频通话、富媒体收发的服务端联调仍在进行中。
