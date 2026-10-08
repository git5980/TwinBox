# TwinBox V2 双生盒（容器版）

VMOS Pro 功能复写 · 基于 VirtualApp-2 (GPL) 改造 · Android 16 存活补丁

## 构建
依赖：Android SDK (build-tools 34 + android-34) + ECJ + dalvik-dx.jar (libs/)

```bash
bash build.sh
# 产物 TwinBox-v2.0.apk（dev.twinbox.app / versionCode 28 / versionName 2.0.8）
```

注意：build.sh 内相对路径 `../va2` 指向 VirtualApp-2 原仓库（AIDL 与 lib manifest 来源）。
独立构建请保持该相对结构，或改脚本内 VA2 变量。

### sha256 表怎么看（同步源码必读）
下面每版「需要同步的文件」表是**当版修复时**的指纹，同一个文件在后续版本里可能又被
改过一次（同一文件会出现多行）。同步时**只认最后一行为止**：以每个文件在本文档中
最后一次出现的 sha256 为准（本地改动过的文件会和历史行对不上，正常）。
本次 2.1.46 之后对不上的只有自己改过的：`build.sh`、`MethodInvocationStub.java`、
`MainActivity.java`、`VClient.java`、`KeepAliveService.java`、
`host-manifest-template.xml`、`ContextFixer.java`、`PackageParserEx.java`、
`ApkManifestLauncher.java`、`VBox.java`、`SealedStorage.java`。
版本号照例由 `host-manifest-template.xml` 维护，README 不替你改。

## 功能面（对齐 VMOS Pro）
- 容器引擎：VA 双进程架构（:x 引擎进程 + 主进程），766 源文件完整编译
- 静默安装 APK 进容器（无需系统安装器）
- 主空间应用一键克隆（多开）
- 容器桌面：九宫格 + 分身角标 + 多分身选择启动
- 悬浮球快捷面板（拖拽 / 点按展开 / 外部点击收起）
- 文件中转桥（ACTION_SEND，APK 自动转安装中心）
- Android 12-16 存活：HiddenApiBypass 元反射豁免 + targetSdk 30 姿态

## 交互按钮自查结论（2026 静态审查，见 [BUTTON-CHECK.md](BUTTON-CHECK.md)）
2.0.7 的宿主 UI 有三块整片失效：底部三个按钮从未注册监听、Options Menu 因
NoActionBar 主题永不可达、悬浮球因 `ACTION_DOWN` 返回 false 而点不动。
这些已在 2.0.8 全部修掉，详见下方「2.0.8 修复清单」。

## 2.0.8 修复清单

### 交互按钮全部恢复可用
| # | 问题 | 修法 |
|---|---|---|
| 1 | 底部「装 APK」「克隆应用」「悬浮球」从未 `findViewById`，点击零响应 | `MainActivity.onCreate()` 补注册；「装 APK」直接拉起 SAF 选择器，「克隆应用」进主机列表页 |
| 2 | 「安装应用 / 悬浮球 / 结束全部」本来就写在 `onCreateOptionsMenu`/`onOptionsItemSelected` 里，代码没错；但主题 `Theme.Material.NoActionBar` 下**没有 ActionBar 也没有溢出按钮**，`targetSdk 30` 又关掉 legacy 浮动菜单 → `onCreateOptionsMenu` 压根不被调用，整份菜单是死代码 | 主题换成 `Theme.Material`（框架提供 ActionBar），菜单 XML 独立到 `res/menu/menu_main.xml`，原实现直接恢复工作。布局不摆 Toolbar，避免双标题栏 |
| 3 | 容器内装进第一个应用后，空态按钮消失，主界面再无进安装页的入口 | 底部按钮 + ActionBar 溢出菜单双常驻入口，不再依赖 `mEmpty` 的显隐 |
| 4 | 悬浮球 `OnTouchListener` 的 `ACTION_DOWN` 分支 `return false`，ImageView 不 clickable → 整棵树不消费 DOWN → MOVE/UP 收不到，球既拖不动也点不开 | `ACTION_DOWN` 改为 `return true`，并补 `ACTION_CANCEL` |
| 5 | 容器为空时 `togglePanel()` 对从未 `addView` 的 `mPanel` 调 `removeView` → `IllegalArgumentException` 崩溃 | 抽出 `safeRemove()`：只 remove `isAttachedToWindow()` 的 view + try/catch 兜底 |
| 6 | 悬浮面板弹出来没有关闭手段（原来只能再点球，而球本来就是废的） | 面板加关闭按钮、`FLAG_WATCH_OUTSIDE_TOUCH` 点击外部收起、再点球收起 |

### 容器桌面内容为空
7. `VBox.listInstalledRaw()` 用宿主 PM 解析容器包：宿主主进程里 VirtualApp 对
   `GetApplicationInfo / GetInstalledPackages / QueryIntentActivities` 的 hook 全部是
   `isAppProcess()` 门控，`ProcessType.Main` 下 `useProxy` 为 false，直接打到系统 PMS。
   SAF 安装的 APK（宿主没有同名包）抛 `NameNotFoundException`，被
   「单包失败不拖垮列表」的 catch 静默吞掉 —— 用户装完 APK 回桌面看到的还是「容器是空的」。
   改为引擎 PM `VPackageManager.get().getApplicationInfo(pkg, 0, userId)`
   （与 `VirtualCore.createShortcut()` 同款姿势）+ 宿主 PM 兜底 + 包名兜底三级降级，
   并且每级都留 TLog 痕迹，不再静默。
8. `VBox.isCoreReady()` 判断 `VirtualCore.get() != null`，而 `gCore` 是非空静态单例，
   判断永远为 true，「引擎初始化异常」是死分支。改用引擎真实启动标志 `isStartup()`。
9. 「应用信息」跳宿主 `ACTION_APPLICATION_DETAILS_SETTINGS`，容器专属包在宿主没安装，
   打开的是空白页且不抛异常、兜底 Toast 也不触发。改为自建详情弹窗
   （包名 / 版本 / 分身数 / userId 列表 / 容器内 APK 路径 / 宿主机是否同包）。
10. 多开出的第 2、3 个分身原来只能看角标 `×N`，点图标永远只启动 `users[0]`。
    现在多分身态点击先弹分身选择器。
11. `saveLauncher()` 里「SAF 安装场景」是个空分支，注释说要从容器取第一个 activity 却什么都没做。
    补齐：先问宿主 PM，再问容器 PM (`queryIntentActivities`)，最后 `getLaunchIntent` 兜底，
    三级都失败才告警。
12. 启动失败不再无声无息：`VBox.launch()` 拿 `VActivityManager.startActivity` 的返回值
    （0 = START_SUCCESS），非 0 直接 Toast。

### 安装中心
13. 「APK」按钮 `setType("application/vnd.android.package-archive")` 在部分 OEM 文件选择器上
    返回空列表。放宽为 `setType("*/*")`，APK 与否拿到 Uri 后自己判。
14. 主空间列表在主线程做重活：`onCreate` 跑 `pm.getInstalledPackages(0)`，
    每行 `getView` 再各打一次 `isInstalledInBox()` 跨进程 IPC。改为后台线程一次拉列表 +
    一次拉「容器内已装包名集合」，角标从内存集合读。
15. `reloadHostApps()` 每次 `new HostAppAdapter()`，克隆完滚动位置全丢。改为复用 adapter 只 swap 数据。
16. 已克隆应用点一次是「克隆进盒」的重复语义。现在改成触发多开（`MultiOpenTask`），
    与 `MainActivity` 长按菜单的「多开一个分身」一致。

### 中转桥与前台服务
17. 分享 APK 时 `InstallActivity` 与 `BridgeActivity` 的 intent-filter 同时命中，系统每次弹
    「用什么打开」。`BridgeActivity` 收到 APK 时显式转给 `InstallActivity` 消歧；
    文件名优先取 `OpenableColumns.DISPLAY_NAME`，不再从 `getLastPathSegment()` 里切 documentId。
18. manifest 只声明了 `FOREGROUND_SERVICE`，而服务是 `foregroundServiceType="specialUse"`。
    Android 14+ 还要求 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 +
    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性，已补齐；同时补 `POST_NOTIFICATIONS` 声明。
    `startForeground` 失败时降级为普通服务，不让悬浮球整体失效。
19. `MainActivity` 新增 `FloatingService.isRunning()` 做开关态：悬浮球运行中时按钮高亮，
    再点一次是关闭。

## 2.1.17 修复清单（Android 16 / SDK 36 容器内应用打开闪退）

### 现象
`video.player.videoplayer` 装进容器后一点开就闪退，宿主 logcat：

```
E AndroidRuntime: FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to start activity ComponentInfo{video.player.videoplayer/
com.inshot.xplayer.activities.SplashActivity}: java.lang.ClassCastException:
java.lang.Long cannot be cast to java.lang.Integer
Caused by: java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Integer
    at com.lody.virtual.client.hook.proxies.pm.MethodProxies$QueryIntentServices.call(MethodProxies.java:591)
    at android.app.ApplicationPackageManager.queryIntentServicesAsUser(ApplicationPackageManager.java:1767)
    at com.android.billingclient.api.b.g(...)          ← Google Play Billing 探活
    at com.inshot.xplayer.activities.SplashActivity.onCreate(...)
```

guest 进程 ID 22052（= 宿主 `dev.twinbox.app:p0`），Android 16（SDK 36）。

### 根因
Android 14+ 起 `IPackageManager` 一系列方法签名先后被改过两次，VirtualApp 引擎里
按老签名写死的 `(Integer) args[N]` / `(int) args[N]` 拆箱必然踩雷：

| 形态 | 例子 | 老代码假设 |
|---|---|---|
| ① flags 前插 long 版本号（2.1.9 已修） | `getApplicationInfo(String, long, int, int)` | `(int) args[1]` |
| ② **flags 由 int 原地变 long（本次）** | `queryIntentServices(Intent, String, long, int)` | `(Integer) args[2]` |

本次崩溃走的是形态②：billingclient 在 `SplashActivity.onCreate` 里查 host 的
billing service，`ApplicationPackageManager.queryIntentServicesAsUser` 调
`IPackageManager.queryIntentServices`，新签名第三个参数是 `long`，
`MethodProxies$QueryIntentServices.call` 里 `(Integer) args[2]` 当场
`ClassCastException`，异常顺着 `ActivityThread.performLaunchActivity` 把 guest
主线程打死 → 容器内应用一点开就闪退。

同族的 `queryIntentActivities` / `queryIntentReceivers` /
`queryIntentContentProviders`（同样 `(Integer) args[2]`）和 `getInstalledApplications`
（`(Integer) args[0]`）都是同一颗雷，只是这几个 hook 这次还没被踩到。

### 修法
1. `MethodProxies.flagsAt()` 升级为三种签名形态全兼容：
   老签名直接取；long 原地替换 flags 时截断回 int；long 是插在 flags 前的版本号时，
   用反射参数表定位「第一个 long 后面的 int」。
2. `QueryIntentServices` / `QueryIntentActivities` / `QueryIntentReceivers` /
   `QueryIntentContentProviders` / `GetInstalledApplications` 全部改走 `flagsAt()`，
   消灭所有 `(Integer) args[2]`。
3. `MethodInvocationStub$HookInvocationHandler.invoke` 加兜底：hook 自身再抛异常
   （下一次框架签名再变时）不再让 guest 陪葬——本次 hook 作废、透传原始系统实现，
   只少一层虚拟化过滤，最多少点功能而不是闪退。

版本号 `versionCode 45 → 46`，`versionName 2.1.16 → 2.1.17`。

### 顺带记录（非本次闪退原因，日志里已有降级处理）
- `NativeEngine.nativeIOForbid/Whitelist/IORedirect/EnableIORedirect`、
  `nativeLaunchEngine` 全部 `UnsatisfiedLinkError`：产物 `TwinBox-v2.0.apk` 里
  **没有 `lib/arm64-v8a/libv++_64.so`**（`build.sh` 没有打包 .so 的步骤，
  这些 .so 来自 `../va2` 原仓库）。所有 native 调用点都有 try/catch 降级，
  不致命，但 IO 重定向/引擎 native hook 实际是空转，需要补打包步骤。
- guest 自带 `libitcore.so` 的 `JNI_OnLoad` 返回 `JNI_ERR`（`zoecore::: signature is error`）
  与 `libSignatureKiller.so not found`：guest 自己的签名校验链在容器里失败，
  已降级为 `System.err` 打印，不是容器崩溃。

## 2.1.18 修复清单（Android 16 / SDK 36 WebView 沙箱进程起不来）

### 现象
2.1.17 修掉 queryIntentServices 的 ClassCastException 之后，同一个 guest
（video.player.videoplayer）能起来，但一加载 WebView 就整进程退出：

```
FATAL EXCEPTION: Chrome_ProcessLauncherThread
java.lang.SecurityException: BIND_EXTERNAL_SERVICE failed, calling package not owned by calling UID
  at android.app.IActivityManager$Stub$Proxy.bindServiceInstance(IActivityManager.java:6572)
  at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:2282)
  at android.app.ContextImpl.bindIsolatedService(ContextImpl.java:2169)
  at WV.yq.b(chromium-SystemWebViewGoogle6432.aab-...)
...
E TwinBox/V|VC: GUEST UNCAUGHT in thread [Chrome_ProcessLauncherThread] pid=13209 : ...
I Process : Quit itself, Pid:13209 StackTrace: ... VApp$3 → KillApplicationHandler
```

### 根因
对照 android-16.0.0_r1 的 AOSP 源码（`core/java/android/app/ContextImpl.java` +
`IActivityManager.aidl`）逐条核过：

1. **Android 16 上 `ContextImpl.bindServiceCommon` 不再调用 `bindService`，
   一律走 `bindServiceInstance(...)`**（普通 bind 和 isolated bind 都是）。
   容器只给 `bindIsolatedService`（`BindServiceQ`）和 `bindService` 挂了 hook，
   两条都白挂；`bindServiceInstance` 没有任何 hook，`callingPackage` 一路带着
   guest 包名进 AMS。AMS 侧 `BIND_EXTERNAL_SERVICE` 校验「calling package 必须
   属于 calling UID」，guest 包名不属于宿主 uid → SecurityException → guest 秒退。
2. 顺带：`bindService`/`bindServiceInstance` 的 `flags` 参数自 Android 14 起就是
   `long`（IActivityManager.aidl），老 hook 里 `(int) args[5]` 是 ClassCastException，
   `args[5] = Context.BIND_AUTO_CREATE`（塞 Integer 给 long 形参）会
   IllegalArgumentException。日志里 Firebase 的
   `SecurityException: Not allowed to bind to service ... AppMeasurementService`
   就是这么来的（bindService hook 被兜底放行成 guest 包名裸绑）。
3. 同样的「hook 名单漂移」还有三处，日志里都能看到，均已在本版一起修。

### 修法
| # | 文件 | 修法 |
|---|---|---|
| 1 | `am/MethodProxies.java` | 新增 `BindServiceInstance` hook（自动注册）：先把 `callingPackage`（第 8 个参数）换成宿主包名，再复用 `BindService` 的分流逻辑 |
| 2 | 同上 | 新增 `flagsInt()`：flags 参数 int/long 通吃，截断回 int；重写 `args[5]` 时按原装箱类型回写，避免 long 形参收到 Integer |
| 3 | 同上 | 新增 `GetIntentSenderWithFeature` hook：Android 11+ PendingIntent 走带 `featureId` 的新签名（参数右移一位），把 guest 包名换成宿主；业务逻辑抽成 `handleIntentSender(who, method, args, offset)` 复用 |
| 4 | `job/JobServiceStub.java` + `server/job/VJobSchedulerService.java` | `IJobScheduler.schedule` 在 Android 16 上有 null JobInfo 进来，客户端和服务端都补 null 兜底，不再 NPE |
| 5 | `window/session/BaseMethodProxy.java` | `IWindowSession.relayout` 的 `ViewRootImpl$W.mViewAncestor` 在 Android 16 上已不存在，反射收进 `resolveDecorView()` 并 try/catch，拿不到就放行（水印功能降级，不再刷 NoSuchFieldException） |

版本号 `versionCode 46 → 47`，`versionName 2.1.17 → 2.1.18`。

### 日志里其余非致命告警（已定位，未改行为）
- `IJobScheduler.schedule` 兜底后系统仍拒绝 job（`uid 10041 cannot schedule job in
  video.player.videoplayer`）：容器 IPC 送虚拟 job 的链路在 Android 16 上还有问题，
  WorkManager 重排任务失败但不影响使用。
- `startActivity(APPLICATION_DETAILS_SETTINGS)` 报
  `Permission Denial: package=video.player.videoplayer does not belong to uid=10041`：
  guest 想打开自己（宿主没装）的应用详情页，系统不可能放行，guest 自己捕获。
- `Os.stat` InvocationTargetException：同上一条 2.1.17 记录，libv++_64.so 缺失导致
  的 IO 重定向空转，已有 try/catch 降级。

## 2.1.19 修复清单（兜底日志可诊断化）

2.1.18 之后那版跑起来虽然不崩，但容器日志里还有 22 次
`Hook crash, fallback to origin method`，其中 6 次
`IActivityManager.bindServiceInstance : ClassCastException: Long cannot be
cast to Integer` 只有一行 message、没有堆栈，没法判断是引擎深处抛的还是源码
同步遗漏。（后经 2.1.20 的对照表证实为部分文件未同步。）

改法（[MethodInvocationStub.java](third/com/virtual/client/hook/base/MethodInvocationStub.java)）：
兜底日志补上 **完整参数（`args=[...]`，args 的 toString 自己也有 try/catch 兜住）+
完整堆栈**。VLog 会同时写 logcat 和 TLog 文件，不依赖导 logcat。兜底行为不变：
hook 作废、透传原始系统实现。

这一版的实战价值立刻体现：2.1.20 的 job 参数布局就是这么定位到的
（`args=[[null, (job:...AppMeasurementJobService)]]` 一行直接说明 args[0] 被
换成了 null）。

## 2.1.20 修复清单（Android 16 IJobScheduler 参数布局变了）

### 新日志确认的好消息
`twinbox-20261005.log` + `05_10-15-50-48_354.log` 对照看，15:50 这次跑的构建：
- **没有 FATAL**，guest 全程活着（SplashActivity → PermissionActivity，WebView 渲染进程也拉起来了）。
- 2.1.19 的兜底日志（`args=[...]` + 完整堆栈）生效了，第一次不用猜。
- logcat 里 **已经没有 `relayout` 和 `bindServiceInstance` 的兜底**——上一版的
  `resolveDecorView()` try/catch 和 `flagsInt()` 改造确实进构建了。

### 剩下的真问题：IJobScheduler 参数前面被插了一个
兜底日志给出的决定性证据（15:50:41.140 / .139）：

```
Hook crash, fallback to origin method: IJobScheduler.schedule
  args=[[null, (job:1596167070/video.player.videoplayer/
  com.google.android.gms.measurement.AppMeasurementJobService)]]
  at com.lody.virtual.client.hook.proxies.job.JobServiceStub$schedule.call(JobServiceStub.java:65)

Hook crash, fallback to origin method: IJobScheduler.cancel
  args=[[null, 1596167070]]
  at com.lody.virtual.client.hook.proxies.job.JobServiceStub$cancel.call(JobServiceStub.java:114)
```

Android 16 上 `IJobScheduler.schedule/cancel/getPendingJob/enqueue` 前面都被插入了一个
**对象型前导参数**（args[0] 实测为 null，可能是 caller/token 一类），真正的
`JobInfo` / `jobId` 跑到了 args[1]。于是：
- 老 `cancel` 的 `(int) args[0]` 对 null 拆箱 → NPE，整个 cancel 走兜底，容器侧
  job 永远 cancel 不掉（新日志 8~13 次）。
- 2.1.18 的 `args[0] instanceof JobInfo` 守卫会让 `schedule` 交回系统，系统又以
  guest 包名拒绝（`cannot schedule job in video.player.videoplayer`），Firebase 的
  AppMeasurementJobService 一直排不上——兜底日志里那条
  `JobServiceStub.java:65`（= `method.invoke`）的 InvocationTargetException 就是它。

### 修法（全部改成按类型取参，签名怎么插都不受影响）
| 文件 | 改法 |
|---|---|
| `job/JobServiceStub.java` | `schedule`/`enqueue` 用 `ArrayUtils.getFirst(args, JobInfo.class)` 找 JobInfo；`cancel`/`getPendingJob` 用 `indexOfFirst(args, Integer.class)` 找 jobId（`indexOfFirst` 本来就会跳过 null 元素），找不到才交回系统 |
| `server/job/VJobSchedulerService.java` | `getPendingJob()` 里 `mScheduler.getPendingJob(job.clientJobId)` → `entry.getValue().virtualJobId`：宿主 JobScheduler 只认 virtualJobId，用 guest 的 id 去查必然 null |

需要同步的文件（sha256）：

| 文件 | sha256 |
|---|---|
| `third/com/virtual/client/hook/proxies/job/JobServiceStub.java` | 5b98cf605904b801751e3a47e93371f9c03ca24dc32fcf36f841b7445f4c8694 |
| `third/com/virtual/server/job/VJobSchedulerService.java` | 613ecda06c99c4882c42b7139d06ad7847d7ca8d29084ecc528429005618099c |

版本号由 `host-manifest-template.xml` 维护（当前已是
`versionCode=50 / versionName=2.1.21`，本轮不覆盖，出包时顺带 +1 即可）。

### 仍未处理的已知噪音（不影响运行，先记账）
- `Os.stat` 兜底（几十次）：`ErrnoException: stat failed: ENOENT`，路径是容器内
  `.../virtual/data/user/0/<pkg>/shared_prefs/*.xml`。根因还是
  `TwinBox-v2.0.apk` 没有 `libv++_64.so`、IO 重定向空转（见 2.1.17 记录），
  SharedPreferences 首次读不到文件会自动建，guest 行为正常，只是每条都会触发兜底并
  多调一次真实 stat。要彻底消噪音得把 .so 打进去。

## 2.1.22：验证记录 + 打包引擎 native .so

### 18:27 会话全部清零（05_10-18-27-07_011.log）

| 指标 | 2.1.19（15:50） | 2.1.20（18:27） |
|---|---|---|
| FATAL / 进程退出 / ANR | 0 | **0** |
| `IJobScheduler.schedule` / `cancel` 兜底 | 6 / 8 | **0 / 0** |
| `cannot schedule job in video.player.videoplayer` | 有 | **0** |
| `bindServiceInstance` / `relayout` 兜底 | 0 / 0 | 0 / 0 |
| `Not allowed to bind` / `BIND_EXTERNAL_SERVICE` | 0 | **0** |
| `Os.stat` ENOENT 兜底 | 16 | 16（见下） |
| `APPLICATION_DETAILS_SETTINGS` startActivity 兜底 | 2 | 2（见下） |

2.1.20 的 job 改造确认生效：容器侧 job 排得上、也 cancel 得掉；guest（`:p0`）从启动
到日志结束一直活着。

### 还剩两条（都不影响运行）
1. `Os.stat` ENOENT：根因 `NativeEngine.<clinit>` 每期都在打
   `dlopen failed: library "libv++_64.so" not found`——产物 APK 里没有引擎 native 库，
   IO 重定向空转。SharedPreferences 首次读不到会自动建，guest 行为正常。
2. `APPLICATION_DETAILS_SETTINGS` 的 startActivity：guest 想打开自己（宿主没装）的
   应用详情页，AM 必然拒（`Permission Denial: package=video.player.videoplayer does
   not belong to uid=10041`），guest 自己捕获，属预期。

### 本轮改动：`build.sh` 终于打包 libv++_64.so
第 6 步（打包签名）里加了「native .so」块：按 `arm64-v8a`/`armeabi-v7a`/`x86_64`/`x86`
在 `../va2` 下 find `libv++_64.so` / `libv++.so`，命中就按标准 entry 名
`lib/<abi>/<so>` 塞进 unsigned.apk（zipalign / apksigner 流程不变）；一个都没找到
只打 WARN 不失败，保证没有 va2 的独立构建仍能出包。已用假 va2 仓库干跑验证：
3 个 .so 全部按 `lib/<abi>/<so>` 入包、无 va2 时只告警且退出码 0。

需要同步的文件：`build.sh`（以及 README）。注意产物 APK 从此会带引擎 native 库，
**第一次装上后 IO 重定向才真正生效**——装完再抓一份日志对照，`Os.stat` 噪音应该归零。

## 2.1.23-24 修复清单（「guest 身份泄漏给系统」系统性收敛，不针对任何应用）

### 崩溃（crash-com-puretone-player-05_10-19-38-30_177.log）
新装进容器的 `com.puretone.player`（纯音 1.2.19）启动即闪退，连崩两次
（pid 22425 / 23043）：

```
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to start activity {com.puretone.player/...MainActivity}:
java.lang.SecurityException: Permission Denial: package=com.puretone.player
does not belong to uid=10041
  at android.app.IActivityTaskManager$Stub$Proxy.startActivity(IActivityTaskManager.java:2193)
  at com.lody.virtual.client.hook.base.MethodInvocationStub$HookInvocationHandler.invoke(MethodInvocationStub.java:226)
  at android.app.Instrumentation.execStartActivity(...)
  at android.app.Activity.requestPermissions(Activity.java:6030)
  at com.puretone.player.MainActivity.requestPerm(MainActivity.java:146)
```

即 `MainActivity.onCreate → requestPermissions` 申请运行时权限这一步被打死。

### 根因（对着 android-16.0.0_r1 AOSP 源码核过）
`ActivityTaskManagerService.startActivityAsUser` 每次调用都执行
`assertPackageMatchesCallingUid(callingPackage)`（`ATMS.isSameApp`：callingPackage
必须属于 callingUid）。而容器钩子里把「调用者包名」传给系统时用的还是 **guest 包名**：

- Android 12+ 起 `IActivityTaskManager.startActivity` 在 `callingPackage` 后面插了
  一个 `callingFeatureId`，老代码 `args[intentIndex - 1] = getHostPkg()` 指到的其实是
  **featureId**（`intentIndex` 现在是 3），真正的 callingPackage 在 `args[1]`，
  从来没被改过；
- 于是所有透传给系统的 startActivity 都带 guest 包名 → AMS 拒。
  这次纯音申请的 intent 是
  `{act=android.content.pm.action.REQUEST_PERMISSIONS pkg=com.android.permissioncontroller}`
  （外部系统组件，`isSameApp` 过不了），异常在 `onCreate` 里没人接 → FATAL。
- 同一份 TLog 里 14 次 `Permission Denial: package=<guest> does not belong to uid=10041`
  全部来自这一处（2 次 REQUEST_PERMISSIONS + 4 次本容器自己的
  `APPLICATION_DETAILS_SETTINGS`），一并修掉。

### 修法（[am/MethodProxies.java](third/com/virtual/client/hook/proxies/am/MethodProxies.java)，`StartActivity.call` 开头）
不猜下标，直接扫顶层参数：**把等于 guest 包名的字符串参数换成宿主包名**（真正的调用者
本来就是宿主进程）。各签名布局（`startActivity` / `startActivityAsUser` /
`startActivityAsCaller` / `startVoiceActivity`）都对得上，且只影响透传给系统的路径
（容器内 activity 直接走 `VActivityManager`，不经过这里）。改完之后：
权限申请由「宿主应用」身份弹给系统权限控制器 → 用户授权 → 权限落在宿主 uid →
guest 的 `checkSelfPermission` 看到已授权；结果沿现有 ShadowActivity result 通道
回到 guest 的 `onRequestPermissionsResult`。

### TLog 顺带确认：libv++_64.so 还没进构建
`twinbox-20261005.log`（当天 18:25→19:38）里 `libv++_64.so` / `nativeIOForbid` /
`nativeLaunchEngine` 仍报 51 次，`Os.stat` 兜底 34 次、`Os.chown` 1 次
（`ContextImpl.ensurePrivateDirExists` 对 `.../com.puretone.player/code_cache` chown
ENOENT，同源）。说明上一轮加的 `build.sh` .so 打包块**还没被实际构建过**
（或探测路径没命中）——重新构建时看第 6 步附近有没有
`+ lib/arm64-v8a/libv++_64.so <= ...` 这行；如果打出的是 WARN，就把
`build.sh` 里探测路径按照你 `../va2` 的真实目录加一项。

需要同步的文件：

| 文件 | sha256 |
|---|---|
| `third/com/virtual/client/hook/proxies/am/MethodProxies.java` | fd7588381818350d380fe8266109b219b5f0bdfe3e0deecdd7b8a6285d2ab630 |
| `build.sh` | 41380de1478c5d57e548fce7f1f8bbbe428b22b3ffe4fb53a094da433f5ab1a7 |

版本号由 `host-manifest-template.xml` 维护（当前 `versionCode=50 / versionName=2.1.22`，出包时 +1）。

### 2.1.24：同一类问题做成一套机制，不再逐个应用、逐个方法打补丁
「把 guest 包名当调用者身份传给系统」是一整类问题（XPlayer 撞 WebView bind、
纯音撞 requestPermissions，下一个应用会撞别的方法），所以这轮对着
`android-16.0.0_r1` 的 AIDL + AMS 源码把**整个身份泄漏面**列了一遍，集中收口：

**1. 攻击面清点**（AOSP 侧会校验 `callingPackage` 是否属于调用 uid 的地方）
- `ActivityTaskManagerService`：`startActivityAsUser`（`startActivity` 系列全部
  委托它）、`startActivities`、`startActivityAndWait`、`startActivityWithConfig`、
  `startVoiceActivity`、`startAssistantActivity`、`startActivityFromGameSession`、
  `moveTaskToFront`、`getAppTasks`、`startActivity(s)InPackage`
  → 每个方法都 `assertPackageMatchesCallingUid(callingPackage)`。
- `ActiveServices`：`BIND_EXTERNAL_SERVICE` 分支
  `isSameApp(callingPackage, callingUid)`。
- `ActivityManagerService`：`getIntentSender*` / `peekService` 的包名一致性。

**2. 一套机制**（[MethodParameterUtils.java](third/com/virtual/client/hook/utils/MethodParameterUtils.java)）
- 新增 `CALLER_PKG_ARG` 表：23 个方法 → 「调用者包名」在 args 里的下标
  （照 AIDL 实裁，见源码注释里的清单）；
- `replaceCallerPkg(Method, args)`：只把这一列从**运行时当前 guest 包名**换成宿主
  包名，非 guest 进程 / 下标越界 / 值不是 guest 包名时一律原样返回——
  不限定任何应用，也不碰 intent/目标包名，和需要在钩子里保留 guest 身份的业务
  （如 `GetIntentSender` 的 creator）互不干扰；
- 调用点两处，覆盖两种路径：
  1. [MethodInvocationStub.invokeOrigin()](third/com/virtual/client/hook/base/MethodInvocationStub.java)——
     **没有专门钩子接**的方法（`startAssistantActivity`、`startActivityFromGameSession`、
     `startActivityInPackage`、`startActivitiesInPackage`、`peekService` 等）和
     **钩子崩溃兜底**的路径都走这里；
  2. 钩子内部自己 `method.invoke(who, args)` 透传的点（`StartService` / `BindService` /
     `PeekService`；`StartActivity` 用的是同义的按包名扫描），因为这类调用不经过
     `invokeOrigin`。
- `StartActivity.call` 原有的按包名扫描保留（它是最先崩的路径，扫描比查表更耐签名
  变动），两者作用在同一列上，幂等。

**3. 另一类系统性问题（参数类型 int→long）也一起核过了**
- 照 `android-14.0.0_r1` ↔ `android-16.0.0_r1` 的 `IActivityManager.aidl` 逐条 diff：
  `bindService`/`bindServiceInstance` 的 `long flags` 在 14 就已定型，16 没有新的
  int→long 变化；IAM 侧只有 `bindBackupAgent`/`serviceDoneExecuting` 等**参数个数**
  变化（容器没钩这些）。
- 已改的活路径：`bindService*`（`flagsInt`）、`IJobScheduler.*`（按类型取参）、
  PM `queryIntent*`/`getInstalled*`（`flagsAt`）。全树还剩 47 处 `(int) args[N]`
  原始强转，都在日志里没出现过的路径上；一旦真有应用踩到，2.1.19 的兜底日志会带
  堆栈+参数直接指到行号，改一行即可，不会再演变成闪退。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/utils/MethodParameterUtils.java` | 1aca133068f26f6285c038095855650910ba756f0139f34447459a745796ec27 |
| `third/com/virtual/client/hook/base/MethodInvocationStub.java` | b8df29a7fb319a988cfe6112454a23e5873c7bdc95e25f908246c31565f65173 |
| `third/com/virtual/client/hook/proxies/am/MethodProxies.java` | fd7588381818350d380fe8266109b219b5f0bdfe3e0deecdd7b8a6285d2ab630 |


## 2.1.25：运行日志默认落外部目录（Download/TwinBox，不授权也能落）

### 之前的状况
TLog 的落盘策略是「有「所有文件访问」权限才写公共 `Download/TwinBox`，否则回退
`/storage/emulated/0/Android/data/dev.twinbox.app/files/logs/`」。实际抓到的日志
头部一直是 `log dir = /storage/emulated/0/Android/data/dev.twinbo...`，
说明 **all-files 没授权 → 一直写在应用私有目录里**：文件管理器默认看不到、
Android 11+ 还得靠 adb 或特殊工具才能取出来。

### 现在（[TLog.java](src-host/dev/twinbox/app/TLog.java) 三级策略）
| 优先级 | 模式 | 落点 | 说明 |
|---|---|---|---|
| 1 | `MODE_DIRECT` | `Download/TwinBox/twinbox-YYYYMMDD.log` | 已授权 all-files：直接 `FileWriter` 追加（最稳） |
| 2 | `MODE_MEDIASTORE` | 同一路径 | **未授权也走这条**：Android 10+ 通过 `MediaStore.Downloads` 写自己插入的下载类文件免权限，先按 `DISPLAY_NAME+RELATIVE_PATH` 查当天文件、没有就 insert，再以 `wa` 模式追加（`wa` 不支持自动降级 `rw`+seek 到文件尾） |
| 3 | `MODE_PRIVATE` | `files/logs/` | 前两级都失败才回退，日志不丢 |

- 宿主 + 引擎（VLog）+ guest 的日志本来就走 TLog，所以**全部**落在同一处；
- 按天滚动、保留最近 7 份的逻辑对三种模式都有（MediaStore 模式用 `DISPLAY_NAME`
  排序删老的）；
- `Download/TwinBox` 目录创建时会做 `.probe` 写探测，写不进去立即降级，不赌。

### 配套
- [MainActivity](src-host/dev/twinbox/app/MainActivity.java) 状态栏改为显示
  `TLog.dirDesc()` 的实际落点，非公共目录时加「(私有目录·未落公共)」提示；
- 「所有文件访问」对话框从「需要」改成「建议」：文案说明日志默认已经落在
  `Download/TwinBox/`，授权只是把通道从 MediaStore 换成直写（更稳、更通用）。

### 装完后怎么确认
启动一次，看状态栏那行是不是 `日志: Download/TwinBox/twinbox-YYYYMMDD.log`；
或者直接在文件管理器打开 `Download/TwinBox/`。给你的日志现在从那儿取就行，
不用再翻 `Android/data`。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `src-host/dev/twinbox/app/TLog.java` | 65205707175b207828f529517d1de4b66f9cda0c28ef91ff43c4c9190cd4a47b |
| `src-host/dev/twinbox/app/MainActivity.java` | d4225f33b00f7c425f14e4283724e9efbaf809956924a1545896523b77b93c6a |

## 2.1.26：Toast 窗口崩（addToDisplayAsUser 钩子漏注册）

### 崩溃（crash-com-puretone-player-05_10-20-49-57_453.log，连崩两次）
```
FATAL EXCEPTION: main
java.lang.RuntimeException: Adding window failed
  at android.widget.Toast$TN.handleShow(Toast.java:781)
Caused by: java.lang.SecurityException: Package com.puretone.player not in UID 10041
  at android.view.IWindowSession$Stub$Proxy.addToDisplayAsUser(IWindowSession.java:1332)
  at com.lody.virtual.client.hook.base.MethodInvocationStub.invokeOrigin(MethodInvocationStub.java:273)
Caused by: android.os.RemoteException:
  at com.android.server.wm.WindowManagerService.doesAddToastWindowRequireToken(WindowManagerService.java:2297)
  at com.android.server.wm.WindowManagerService.addWindow(WindowManagerService.java:1894)
```

### 根因（照 android-16.0.0_r1 的 IWindowSession.aidl / WindowManagerService.java 核过）
`IWindowSession` 里带 `WindowManager.LayoutParams` 的方法：`addToDisplay`、
**`addToDisplayAsUser`**、`addToDisplayWithoutInputChannel`、`relayout`、
`relayoutAsync`。容器只注册了 `add` / `addToDisplay` / `addToDisplayWithoutInputChannel` /
`addWithoutInputChannel` / `relayout` 这些**旧名字**；Android 12+ 的
`ViewRootImpl.setView` 实际调用的是 `addToDisplayAsUser`，于是
[BaseMethodProxy.beforeCall()](third/com/virtual/client/hook/proxies/window/session/BaseMethodProxy.java)
里那句 `attrs.packageName = getHostPkg()` 从来没执行过 → 窗口的 packageName 一直是
guest 包名。WMS 侧 `addWindow` 对 `TYPE_TOAST` 会调
`doesAddToastWindowRequireToken(attrs.packageName, callingUid, parentWindow)`：
没有挂靠窗口时直接 `isSameApp(packageName, callingUid)` 校验，guest 包名不属于宿主 uid
→ `SecurityException: Package ... not in UID 10041` → Toast 一弹整个 guest 进程就走。

（活动窗口不崩只是因为这条校验只针对 toast；所以之前 UI 一直好的，Toast 是第一颗雷。）

### 修法（[WindowSessionPatch.java](third/com/virtual/client/hook/proxies/window/session/WindowSessionPatch.java)）
补注册两个漏掉的方法名，走的还是原来的 `BaseMethodProxy`（`beforeCall` 里
`attrs.packageName = getHostPkg()` + 悬浮窗 type 归一化 + 悬浮窗禁用策略）：
- `addToDisplayAsUser`（带悬浮窗禁用策略的匿名子类，和 `addToDisplay` 一致）；
- `relayoutAsync`（复用 `Relayout`，一并把异步 relayout 的水印/attrs 处理接上）。

改完后 toast 的 `attrs.packageName` 是宿主包名 → `isSameApp` 通过 →
`targetSdk(30) >= O` → 要求 window token 为 `TYPE_TOAST`（框架本来就会给 toast
分配该类型的 token）→ 窗口正常添加。

### 顺带确认两件好事（同一份日志）
- 2.1.25 的日志落盘生效：TLog 头部 `log dir = Download/TwinBox/twinbox-20261005.log
  (公共Download·免权限通道)`；
- 2.1.23-24 的身份改写生效：guest 的运行时权限申请已经能拉起系统
  `com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity`
  （logcat `Displayed ... GrantPermissionsActivity`）。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/proxies/window/session/WindowSessionPatch.java` | e4f41ae5fe6873416b3dea9db01750af59e364cfc5f3a4020f9a72c78deb9e61 | 补注册 addToDisplayAsUser / relayoutAsync |

## 2.1.27：钩子自检（换新 guest 应用前的早期预警）

### 为什么加这个
前几轮每次都是「新应用踩到静默失效的钩子」才暴露问题：`bindIsolatedService`
（Android 16 已删除，纯音撞的是 `bindServiceInstance`）、`addToDisplayAsUser`
（Android 12+ 改名，纯音的 Toast 撞的）。这类失效**没有任何日志**——钩子只是
永远不被调用。

### 现在
[MethodInvocationStub.selfCheck()](third/com/virtual/client/hook/base/MethodInvocationStub.java)
在每次构造服务代理时跑一次（纯反射、无副作用）：把该服务已注册的钩子方法名和
**这台机器上真实存在的接口方法名**做差集，结果写进 TLog：

```
I/V|MethodInvocationStub: Hook self-check [IActivityManager]: 41 registered, all alive
W/V|MethodInvocationStub: Hook self-check [IActivityManager]: 41 registered,
     DEAD on this API [bindIsolatedService]
```

换新应用前先看这两行：`DEAD` 里出现的方法名 = 对应虚拟化逻辑在这台机器上整条
失效，新应用只要用到相关功能就会撞（或被兜底放行成 host 裸调）。

## 其它服务 AIDL 14↔16 对比结果（本轮清点）

| 接口 | 方法签名变化 | 说明 |
|---|---|---|
| `IActivityManager` / `IActivityTaskManager` | `bindService*` 的 `long flags`（14 已定型）；其余无 | 16 无新增结构变化；`bindIsolatedService` 被删 |
| `IPackageManager` | **0 处** | 16 新增 19 个方法（归档包/APEX/页大小兼容/包监听等），容器没钩 → 未虚拟化（数据回宿主侧，不崩） |
| `IAccountManager` | **0 处** | — |
| `ISearchManager` | **0 处** | — |

结论：签名漂移类地雷在這几个高频接口上已排完；`IJobScheduler` 的前置参数插入
（2.1.20 已修）是唯一一例结构变化。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/base/MethodInvocationStub.java` | b8df29a7fb319a988cfe6112454a23e5873c7bdc95e25f908246c31565f65173 | selfCheck |
| `third/com/virtual/client/hook/base/MethodInvocationProxy.java` | 3a6141da4be95d59527af148fe6ad21ceb557231072874076224d21eccbde28f | 构造时触发 selfCheck |

## 2.1.28：自检报告解读 + location 钩子补新名（第一个换应用前的实测）

### 自检上线后的第一份实测（twinbox-20261005.log，21:25，Android 16 设备）
`Hook self-check` 跑通了，把各服务「已注册但在这台机器上不存在」的钩子全部列了出来
（标签已从 `[Proxy]` 修成真实服务名）。逐个对着 AIDL 核过，结论分三类：

| 类别 | 例子 | 影响 |
|---|---|---|
| **真死（接口改名/删除）** | `requestLocationUpdates`→`registerLocationListener`、`removeUpdates`→`unregisterLocationListener`、`isProviderEnabled`→`isProviderEnabledForUser`、`addGpsMeasurementListener`→`addGnssMeasurementsListener`（ILocationManager 重构）；`getAccounts`/`removeAccount`/`addSharedAccountAsUser`（IAccountManager 改名）；`enqueueNotification`/`enqueueToastEx`/`getImportance` | 对应虚拟化对 guest **整条失效**：定位管控/虚拟定位不生效、账号操作不接管 |
| **跨服务注册（本 fork 的复制粘贴）** | `MountServiceStub`（mount 服务）上挂了 6 个 storage-stats 钩子名 | 死代码，真身在 `StorageStatsStub`（storagestats）上，功能不受影响 |
| **正常（老名 + 新名都注册了）** | `enqueueNotificationWithTag`、`getLastLocation`、`registerGnssStatusCallback`、`bindServiceInstance`、`addToDisplay*`、`IJobScheduler.*`、`queryIntentServices`、`checkPermission` | 这些路径是活的 |

也就是说：**崩溃类问题已经收干净，剩下的是「虚拟化缺口」**——新应用如果用到
定位订阅、账号操作、GNSS 监听，拿到的是宿主侧行为（定位管控失效、账号不接管），
表现为功能/隐私策略不生效，而不是闪退。

### 本轮修法（location，影响面最大的一项）
[LocationManagerStub.java](third/com/virtual/client/hook/proxies/location/LocationManagerStub.java)
+ [MethodProxies.java](third/com/virtual/client/hook/proxies/location/MethodProxies.java)：
把 `Hook self-check` 报死的 location 钩子按 Android 16 的 AIDL 名重新注册
（复用同一份 hook 逻辑，别名构造器）：
- `registerLocationListener` / `registerLocationPendingIntent`（原 `requestLocationUpdates`）
- `unregisterLocationListener` / `unregisterLocationPendingIntent`（原 `removeUpdates`）
- `isProviderEnabledForUser`（原 `isProviderEnabled`，`IsProviderEnabled` 加了名字构造器）
- `addGnssMeasurementsListener` / `addGnssNavigationMessageListener` /
  `removeGnssMeasurementsListener` / `removeGnssNavigationMessageListener`（原 `*Gps*Listener`）

「禁用定位」这类策略在参数解析之前就返回，所以新签名下一样生效；虚拟定位那条路
新旧签名参数布局不同，拿不准时由 2.1.17 的兜底网降级为透传（不会崩）。

> 下一批按同样套路补：IAccountManager（`addAccountAsUser` / `removeAccountAsUser` /
> `renameAccount` / `getAccountsAsUser`）和 INotificationManager 的
> `enqueueNotificationWithTagPriority` 等 OEM 扩展名——自检报告里都有，等确认要补再说。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/proxies/location/LocationManagerStub.java` | 4163d51a2cd1b8a418f26bee5b971048b9b4dc18522ff348228492152fd1a014 | 注册 Android 14+ 新名 |
| `third/com/virtual/client/hook/proxies/location/MethodProxies.java` | 45fd304f9392ff0dec757662ea6dc88bf879c4fa0d5b218fef156fd804ea83e6 | IsProviderEnabled 支持别名 |
| `third/com/virtual/client/hook/base/MethodInvocationStub.java` | b8df29a7fb319a988cfe6112454a23e5873c7bdc95e25f908246c31565f65173 | 自检标签改成真实服务名 |

## 2.1.29：音乐播放没声音（虚拟存储把 /sdcard 换成空目录）+ 账号/通知钩子补齐

### 现象（twinbox-20261006.log，00:05，同一台 Android 16 设备）
纯音（com.puretone.player）能起来、不崩、界面正常，但**音乐播放没有声音**。
会话日志里两条关键线索：

```
10-06 00:05:38.337 I/V|NativeEngine: OpenDexFileNative(/data/user/0/dev.twinbox.app/virtual/data/app/com.puretone.player/base-1.apk, "null")
10-06 00:05:51.675 W/V|ProviderHook: call: openTypedAssetFile(...content://media/external/audio/albumart/4375580951540192985...) with error
```

`OpenDexFileNative` 是 libv++_64.so 的 native hook 回调打进来的——说明
**上一轮给 build.sh 加的 .so 打包真的生效了**（IO 重定向从「空转」变成「真干活」）。

### 根因：虚拟存储默认开启 + 空目录也重定向
`VirtualStorageService.getOrCreateVSConfigLocked()` 里每个应用的 VS 配置
**默认 `enable = true`**，而 `VClient.startIORelocaterInner()` 只要 enable 就把
`/sdcard`、`/storage/emulated/0/`、`/mnt/sdcard/` 等所有挂载点
`redirectDirectory(mountPoint, vsPath)` 到 **per-app 虚拟存储目录**。

之前 `.so` 缺失、重定向全是 no-op，所以这个默认值一直没人受伤；`.so` 一装上立刻生效：
- guest 的 `/sdcard` 被换成一个**空的 per-app 目录**；
- MediaStore 列表还能看到歌（查询走系统 provider，不经文件路径）；
- 真按路径开文件播放时落到空目录 → ENOENT → **播放没声音**（照片选择、文件读写同理全灭）。

### 修法（双保险，[VClient.java](third/com/virtual/client/VClient.java) + [VirtualStorageService.java](third/com/virtual/server/vs/VirtualStorageService.java)）
1. **新配置默认关闭**：`VSConfig.enable` 默认 `false`。需要隔离开存储的场景再显式打开。
2. **目录为空就不重定向**：只有虚拟存储目录**非空**（说明这应用真用过）才重定向挂载点；
   空目录 = 从没启用过，不给它改视图。顺带自动修复历史配置（以前被默认值坑成
   `enable=true`、但目录一直空的应用），不需要数据迁移。并打日志
   `virtual storage dir empty, skip mount redirect ...` 便于确认。

### 顺带补齐（自检报死、但有现代名的）
| 文件 | 补的名字 | 说明 |
|---|---|---|
| `notification/NotificationManagerStub.java` | `enqueueTextToast`、`getPackageImportance` | Android 16 的 INotificationManager 把文本 toast 走 `enqueueTextToast`、`getImportance` 改名 `getPackageImportance`（原 `enqueueNotification`/`enqueueToastEx` 已无现代对应，保持现状） |
| 账号侧核查 | 无需补 | `IAccountManager` 的 `addAccountAsUser`/`removeAccountAsUser`/`renameAccount`/`getAccountsAsUser`/`addAccountExplicitly`/可见性系列**本来就已经注册**；自检里死的 `addSharedAccountAsUser`/`getSharedAccountsAsUser`/`removeSharedAccountAsUser`/`renameSharedAccountAsUser` 在 Android 16 已整体删除（只剩语义不同的 `addSharedAccountsFromParentUser(int,int,String)`），容器单用户模型用不到，不做假别名 |

### 下一版日志怎么确认
启动后 TLog 应出现
`virtual storage dir empty, skip mount redirect for com.puretone.player`；
播放音乐应有声音。如果**仍然没声音**，抓一份 logcat（播放时）——
重点看 guest 进程里 `MediaPlayer`/`AudioTrack`/`ExoPlayer` 的报错和
`ProviderHook ... with error` 是否覆盖到音频文件 URI（这轮日志里只有 albumart
封面一条，可能是封面 id 不存在，不足以解释静音）。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/VClient.java` | 2fbbc77291e6dd4aee383a93bb283e73bf6b9e1d1a6d5c06cde3c8fdabfb03d0 | 虚拟存储空目录不重定向 |
| `third/com/virtual/server/vs/VirtualStorageService.java` | 668db6db76b3bba6c6690cd8138819e95ad58d9215da2cb1b11b26ee39501f79 | VS 默认关闭 |
| `third/com/virtual/client/hook/proxies/notification/NotificationManagerStub.java` | 35681b6a0d461fe64db9621d8896944acfdacb5a90d6758f278ca9d8c29d3a32 | enqueueTextToast / getPackageImportance |

## 2.1.30：libc 文件 hook 在 Android 16 上 SIGILL，native IO 重定向默认关闭

### 崩溃（crash-video-player-videoplayer-06_10-00-39-53_112.log）
原生崩溃，**不是 Java 异常，兜底网接不住**：

```
F libc : Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x72d4b7e700
         in tid 11155 (Firebase Backgr), pid 11100   (进程启动才 2 秒)
backtrace:
  #00 rmdir+0                                       (bionic libc.so)
  #01 libjavacore.so Linux_remove(_JNIEnv*,_jobject*,_jstring*)+72
  #03 libcore.io.BlockGuardOs.remove+232
  #09 com.lody.virtual.client.hook.base.MethodInvocationStub.invokeOrigin
  #13 MethodInvocationStub$HookInvocationHandler.invoke
  #19 java.io.UnixFileSystem.delete  →  #20 java.io.File.delete
  #22-#38 <guest 代码 d81.u/ke0.e/cd0.t/...>（Firebase 后台线程清临时文件）
```

logcat 同段确认 `nativeloader: Load .../lib/arm64/libv++_64.so ... ok` +
`NativeEngine: OpenDexFileNative(...)`：**上一轮给 build.sh 加的 .so 打包生效了**，
native 侧第一次真正给 libc 文件函数装 hook，在 Android 16 的 bionic 上把
`rmdir` 打成了非法指令。于是 guest 里任何 `File.delete()` / `Os.remove()`
都会让整个进程 SIGILL 秒死（XPlayer 启动 2s 内的 Firebase 清理就撞上了）。

这跟「IO 重定向刚生效」是同一串：2.1.29 治的是它把 `/sdcard` 换成空目录，
这条是它把 `rmdir` 打成 SIGILL。

### 修法（[VClient.java](third/com/virtual/client/VClient.java)）
`IO_REDIRECT_ENABLE = false`——**默认不再调用 `NativeEngine.enableIORedirect()`**：
- 不装 libc 文件函数 hook → `remove/rmdir/open/stat` 全回到系统原生行为，SIGILL 消失；
- 引擎的 Java 层虚拟化不依赖它（`.so` 装上前一直没开，前面各版 guest 应用都正常）；
- 网络策略、加密包名等其它 native 配置与文件 hook 无关，照常下发；
- `launchEngine` 的 dex/camera/audioRecord hook 照旧（`OpenDexFileNative` 证明它是好的）；
- 启动日志明确打一行 `IO redirect disabled (...)`，下次一看就知道状态。
真需要 native IO 重定向时把常量改回 `true`，并在各 guest 上回归一遍文件删除。

### 顺带确认（这份 logcat 里）
- `bindServiceInstance` / `startActivity` / `relayout` / job 等钩子兜底**一条都没有**
  ——2.1.18–2.1.29 的修复在 XPlayer 上也全部成立；
- 剩下的只有老相识 `Os.stat` ENOENT（shared_prefs 首次读，guest 行为正常）。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/VClient.java` | 2fbbc77291e6dd4aee383a93bb283e73bf6b9e1d1a6d5c06cde3c8fdabfb03d0 | IO 重定向默认关闭（2.1.29 的空目录判断保留，改开关即可复用） |

## 2.1.31：验证记录（06_10-09-29，两个 guest 应用全绿）

### 结论：零崩溃，音乐播放恢复
当天 09:28 起了 4 个 guest 会话（纯音 ×3、XPlayer ×1），logcat + TLog 全量核对：

| 检查项 | 结果 |
|---|---|
| FATAL / Fatal signal（SIGILL 等原生崩溃） | **0**（上一次是 `rmdir` SIGILL 秒死） |
| `Hook crash, fallback` | 仅剩 `Os.stat` ENOENT 噪音（shared_prefs 首读，老相识，无害；**2.1.44 起降为 I 级一行**） |
| 2.1.30 开关生效 | `IO redirect disabled (Android16 libc hook SIGILL on rmdir/remove) — native 文件重定向关闭` 每次 bindApplication 都打 |
| 2.1.29 虚拟存储 | 新配置默认关闭，`/sdcard` 不再被换成空目录 |
| **音乐播放** | logcat 里 guest（pid 13766）的 `AudioPlaybackConfiguration ... type:android.media.MediaPlayer state:started attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC sessionId:3737`，audioserver 侧同一 session 7037~ 的 track 走完 ACTIVE→PAUSED→FLUSHED→start —— **播放链路真的通了** |
| XPlayer（pid 14121，353 行日志） | 无崩溃；`resolveDecorView failed (NoSuchFieldException: No field mViewAncestor)` + `hiddenapi ... denied` 正是 2.1.18 的**干净降级**（Toast/窗口不再闪退，只是水印反射拿不到字段） |
| 纯音（pid 13766） | **E 级日志 0 条** |

### 还剩的已知项（都不影响使用）
1. ~~`Os.stat` ENOENT 噪音~~ → **2.1.44 已降级**：ENOENT 是「文件不存在」的正常返回路径，
   现在打成一行 I 级（`hook ENOENT (expected, file absent): Os.stat args=[[...]]`），不再刷堆栈。
2. XPlayer 的水印反射在 Android 16 上拿不到 `mViewAncestor`（字段已删），
   水印功能对 Android 16 的 guest 不可用——`resolveDecorView` 已按设计降级。
3. native IO 重定向 still off（2.1.30）：需要时把 `VClient.IO_REDIRECT_ENABLE`
   改回 true，但要接受 libc hook 在 Android 16 上的 SIGILL 风险（`rmdir`/`remove`）。

需要同步的文件：无（本轮只补文档）。

## 2.1.32：guest 后台保活（前台服务）——「音乐播放没声音」的真凶是进程被杀

### 现象
纯音能放歌但用户听到「没声音」。06_10-09-29 的 logcat 给出的真相：

```
163.857 OPM-Process: resume pkg: com.android.launcher          ← 用户离开容器（Home）
164.381 Launcher: LRV onStateTransitionComplete finalState=Overview  ← 进最近任务
164.473 OPM-Process: resume pkg: dev.twinbox.app
164.750 ActivityManager: Force stopping dev.twinbox.app appid=10041 user=0: o-stop(40)
164.750 Killing 13766:dev.twinbox.app:p0 (adj 925): o-stop(40)   ← guest 播放进程
164.751 Killing 14121:dev.twinbox.app:p1 (adj 704)
164.753 Killing 12719:dev.twinbox.app   (adj 450)
164.759 Killing 13673:dev.twinbox.app:x  (adj 200)               ← 连引擎进程一起杀
164.780 AudioTrack: pause(815): prior state:STATE_ACTIVE        ← 播放随进程一起中断
```

**音频链路本身是好的**：`AudioPlaybackConfiguration ... type:android.media.MediaPlayer
state:started attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC
sessionId:3737`，audioserver 侧同一 session 的 track 走完 ACTIVE→PAUSED→FLUSHED
（SystemUI/OPlus 音量、路由、AppOps 均无异常）。真正的原因是 **OPlus 的 o-stop /
用户从最近任务划掉容器**，`forceStopPackage` 把 main/:x/:pN 四个进程一起杀，
guest 的 MediaPlayer 陪葬——表现为「没声音」。

### 修法（2.1.32）
| 文件 | 改动 |
|---|---|
| [KeepAliveService.java（新增）](src-host/dev/twinbox/app/KeepAliveService.java) | IMPORTANCE_MIN 的前台服务（specialUse FGS，复用 manifest 已声明的 FGS 权限）：「双生盒正在后台运行」常驻通知，无声音无震动，点击回容器桌面 |
| [host-manifest-template.xml](host-manifest-template.xml) | 声明 `KeepAliveService` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明 |
| [VActivityManagerService.java](third/com/virtual/server/am/VActivityManagerService.java) | `startActivities()`（guest 拉界面）时 `KeepAliveService.start(...)`；`onProcessDied()` 里当 `mProcessNames`/`mPidsSelfLocked` 全空（guest 全部退出）时 `KeepAliveService.stop(...)`，不留常驻通知 |

启动/停止都带 try/catch：Android 12+ 后台进程起 FGS 被拒时只记日志降级，不影响容器功能。

### 还需要用户配合（容器代码管不了的部分）
1. **OPlus 后台白名单**：设置 → 电池 → 双生盒 → 允许后台运行/自启动（关掉「智能耗电保护」对它的优化）；
2. **最近任务里锁一下**双生盒（卡片上锁/下拉加锁），划卡片时就不会 force-stop；
3. 悬浮球（前台服务）可以一起开，双保险。

> 说明：这是「降低被杀概率」，不是免死金牌——用户主动从最近任务 force-stop 是 Android
> 的正常语义（任何后台进程都跑不掉）。日志里那次 adj 200（正在播音频）的进程也被杀，
> 说明触发的是 force-stop 而非 LMK，白名单/锁卡片才是决定性的。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `src-host/dev/twinbox/app/KeepAliveService.java` | 1b6bc0361e0763dc38a964d907c850c53a32ab4ea7bf10346406f36fa5c8d8d7 | 新增：后台保活前台服务 |
| `third/com/virtual/server/am/VActivityManagerService.java` | 7dc1d715795fc6d0127612d7c5e52797a52b2945f21419a49dbf7e99b209f46b | 起停接线 |
| `host-manifest-template.xml` | f9f8ad619bd0aff5146b87c38cffaa50ffb07587c3a3afec13a47985bea6d40f | service 声明 |

## 2.1.33：前台播放也没声音——PLAY_AUDIO 被 appop 静音（包名属于 uid 校验失败）

### 现象
纯音前台播放，进度在走、系统媒体面板有播放态，**就是没声音**。

### 根因（06_10-12-44-47 日志，逐层核到 AOSP 源码）
两个决定性证据：

```
1791261885.154 OplusBtAudioRouteMonitor: ... AudioPlaybackConfiguration piid:5079
   ... state:started attr:usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC sessionId:4073
   mutedState:opPlayAudio                                    ← track 在放但被静音

1791261881.625 E AppOps : Bad call made by uid 1041. Package "com.puretone.player" does not belong to uid 10041.
1791261881.626 E AppOps : java.lang.SecurityException: Specified package "com.puretone.player" under uid 10041 but it is not...
```

对着 `AudioPlaybackConfiguration.toString()` 逐位解码：`mutedState:opPlayAudio` =
`MUTED_BY_OP_PLAY_AUDIO`。链路：

1. audioserver 给播放中的 track 查 `AppOpsManager.OP_PLAY_AUDIO`，参数是 track 的
   AttributionSource（uid + package）；
2. `AttributionSource.myAttributionSource()`（`AudioTrack`/`MediaPlayer` 无 context
   创建时走这条）兜底读 `ActivityThread.currentPackageName()` ←
   `mBoundApplication.appInfo.packageName` = **guest 包名**；而 uid 已是容器对齐过的
   真身宿主 uid(10041)；
3. AppOps 校验「包名必须属于该 uid」→ SecurityException → AudioService 把这条播放
   判 `MUTED_BY_OP_PLAY_AUDIO` → **track 正常播放但输出静音**（音量/路由都正常，
   所以以前怎么查都像「音频链路是好的」）。

容器已有的 `ContextFixer`（context 的 mOpPackageName/mBasePackageName）和
`AttributionSanitizer.sanitizeContext`（context 的 mAttributionSource）覆盖了
「带 context」的路径，唯独漏了这份 **AppBindData 里的 appInfo**（静态兜底路径）。

### 修法（2.1.33）
| 文件 | 改动 |
|---|---|
| [AttributionSanitizer.java](third/com/virtual/helper/utils/AttributionSanitizer.java) | 新增 `hostPackageName()` + `sanitizePackageName(ApplicationInfo)`：把 appInfo 的 packageName 换成宿主包名（幂等、只动这一项、失败只记日志） |
| [VClient.java](third/com/virtual/client/VClient.java) | `bindApplication` 里在「微信特判/fixWeChatTinker」之后调用它——那些要 guest 包名的逻辑先跑完，再换成宿主；同步把微信特判的包名判断改成用方法参数 `packageName`（本来就等于 guest 包名），不再依赖 appInfo |

效果：`myAttributionSource()` 兜底路径报 `(10041, dev.twinbox.app)` → AppOps 双过 →
播放不再被 PLAY_AUDIO 静音。buildin 之外的 attribution/opPackageName 路径
（context 系）本来就已被 ContextFixer/sanitizeContext 覆盖，这次补的是最后一处。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/helper/utils/AttributionSanitizer.java` | a7d7ab7a1745c1b980491dde3f559d3fdaf1826dee6930462aafca903902da8e | sanitizePackageName |
| `third/com/virtual/client/VClient.java` | 2fbbc77291e6dd4aee383a93bb283e73bf6b9e1d1a6d5c06cde3c8fdabfb03d0 | 接线 + 微信特判改用 packageName |

## 2.1.34：2.1.33 引入的回归——requestPermissions 又崩（getCurrentPackage 被改坏）

### 回归现象（crash-com-puretone-player-06_10-15-30-23_428.log）
```
SecurityException: Permission Denial: package=com.puretone.player does not belong to uid=10041
  at IActivityTaskManager$Stub$Proxy.startActivity
  at MethodInvocationStub.invokeOrigin(MethodInvocationStub.java:329)
  at Activity.requestPermissions(Activity.java:6030)
  at com.puretone.player.MainActivity.requestPerm(MainActivity.java:146)
```
正是 2.1.23 修过的那个权限申请弹窗。

### 根因：我自己的 2.1.33 把它打断了
2.1.33 为了修播放静音，把 `mBoundApplication.appInfo.packageName` **原地**改成了宿主包名。
而 `VClient.getCurrentPackage()` 读的就是这个字段：

```java
    public String getCurrentPackage() {
        return mBoundApplication != null ? mBoundApplication.appInfo.packageName : ...;   // ← 现在是宿主包名
    }
```

于是 `MethodProxy.getAppPkg()`（= getCurrentPackage）返回 **dev.twinbox.app**，
2.1.23 的身份改写 `appPkg.equals(args[i])` 再也匹配不到 guest 包名，
2.1.24 的 `replaceCallerPkg` 同理 → 调用者身份（guest 包名）漏给 AMS → 同一个
SecurityException 回来。

### 修法（2.1.34，两处，都在 [VClient.java](third/com/virtual/client/VClient.java)）
1. **不原地改**：`bindApplication` 里改为把 `ActivityThread.mBoundApplication.appInfo`
   换成一份「**宿主包名的 ApplicationInfo 副本**」（`new ApplicationInfo(data.appInfo)` 后
   改 packageName）——框架/AttributionSource 读到宿主包名，而 `data.appInfo` 本体保持
   guest 包名，`instrumentationName`、VClient 内部逻辑全不受影响。
2. **兜底**：`getCurrentPackage()` 优先返回新字段 `mCurrentPackage`
   （bindApplication 时记下的真实 guest 包名），不再依赖任何会被消毒的字段。

两条修复后：
- AttributionSource 兜底路径（`currentPackageName()`）→ 宿主包名 → AppOps 放过、播放出声；
- `getAppPkg()` → guest 包名 → startActivity/bindService/setStreamVolume 的身份改写照常生效。

教训也记在这：**「包名」在容器里有两份语义**——给系统的调用者身份要宿主包名，
给虚拟化逻辑的身份要 guest 包名；2.1.33 混用了一个字段，本轮拆开。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/VClient.java` | 2fbbc77291e6dd4aee383a93bb283e73bf6b9e1d1a6d5c06cde3c8fdabfb03d0 | 副本方案 + mCurrentPackage |
| `third/com/virtual/helper/utils/AttributionSanitizer.java` | a7d7ab7a1745c1b980491dde3f559d3fdaf1826dee6930462aafca903902da8e | sanitizePackageName 保留（备用路径，当前不再调用） |

## 2.1.35：授权「给了容器但 guest 认不到」+ 前台播放静音的 attribution 补漏

### 现象（用户反馈，无新日志，按现象反查代码）
1. 给容器授权后 XPlayer 认不到（checkSelfPermission 还是 denied）；
2. 音乐仍然没声音。

两处都在「guest 的身份/权限要拿宿主真身状态」这条线上，仍是这个项目的主线 bug。

### 修 1：权限查询改查宿主真身
`VPackageManagerService.checkUidPermission()` 的老兜底：

```java
        return VirtualCore.getPM().checkPermission(permission, StubManifest.getStubPackageName(is64bit));
```

`getStubPackageName(is64bit)` 在 32 位引擎路径下返回 **"NO_64BIT"**（StubManifest
里 packageName64 的默认值）——拿它问 PM 永远 DENIED； guest 的 checkSelfPermission
（ContextImpl.checkSelfPermission → AM hook → VActivityManagerService →
checkUidPermission）也跟着永远 DENIED，用户在系统设置/权限对话框里授给「双生盒」
的权 guest 一点看不到。

改法（[VPackageManagerService.java](third/com/virtual/server/pm/VPackageManagerService.java)）：
兜底改查**宿主包（dev.twinbox.app）的真实授权状态**（`getUnHookPackageManager()
.checkPermission(perm, hostPkg)`）。guest 跑在宿主 uid 上，宿主包的授权就是许可真身；
容器里已声明过的权限仍维持原语义（虚拟包声明过即放行）。这样：
系统设置里给容器授的权 ✓、guest 弹权限对话框（经 2.1.23 身份改写后落到宿主身份）
授的权 ✓，guest 的 checkSelfPermission 都能看到。

### 修 2：context 的 attribution 也要换宿主
`ContextFixer.fixContext()` 只改了 `mBasePackageName`/`mOpPackageName`，而
`ContextImpl.mAttributionSource` 可能在 context 创建时就按 **guest 包名**缓存了
（fixContext 运行在 onAttach/onCreate 之后，改不到已缓存的字段）。服务/Activity 的
context 走到音频链路时，`AudioTrack/MediaPlayer` 把 (真身 uid, guest 包名) 的
attribution 下传给 audioserver → 查 PLAY_AUDIO 被 AppOps 拒 → `mutedState:opPlayAudio`
→ 前台播放也没声音。

修法（[ContextFixer.java](third/com/virtual/client/fixer/ContextFixer.java)）：
fixContext 末尾补调 `AttributionSanitizer.sanitizeContext(context)`（uid 对齐真身 +
`mAttributionSource` 整体换成宿主实例）。AppBindData 那份由 2.1.34 的副本方案兜住，
context 这份由这里兜住，两条 attribution 来源都换成宿主。

### 验证要点（下一份日志看这几行）
- `E AppOps: Bad call made by uid 1041 ... does not belong to uid 10041` 不再出现；
- 播放配置里 `mutedState:` 从 `opPlayAudio` 变成 `none`；
- guest 里授权相关 UI 不再报「未授权」。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/server/pm/VPackageManagerService.java` | 2fabd627435651d66d9fc5f1d8d972b7c776e910ca304e7c346ea10992235491 | checkUidPermission 查宿主真身 |
| `third/com/virtual/client/fixer/ContextFixer.java` | 648973c2dd4b9ebbaac53ebb635bf35f04d2acdfaa3a46b879dc0d051df2d7c7 | fixContext 补 attribution 替换 |

## 2.1.36：权限还认不到——2.1.35 只补了一半，另一个重载也在查 "NO_64BIT"

### 现象
2.1.35 之后播放好了，但 XPlayer 仍然读不到已授予的权限。

### 根因：两个重载，只修了一个
`VPackageManagerService` 里权限查询有**两个**入口：

| 入口 | 调用方 | 2.1.35 是否修到 |
|---|---|---|
| `checkUidPermission(is64bit, perm, uid)` | `Context.checkSelfPermission` → AM hook → `VActivityManagerService.checkPermission` | ✅ |
| `checkPermission(is64bit, perm, pkgName, userId)` | `PackageManager.checkPermission(perm, pkg)` → **PM hook**（pm/MethodProxies$CheckPermission） | ❌ 漏了 |

第二个重载里的兜底还是老的 `VirtualCore.getPM().checkPermission(permission,
StubManifest.getStubPackageName(is64bit))`——32 位引擎路径下 `getStubPackageName`
返回 **"NO_64BIT"**（StubManifest.packageName64 的默认值），宿主 PM 查不到这个包
→ 永远 DENIED。XPlayer 走 PM 那条路查权限时，系统设置里给「双生盒」授的权、
权限对话框里授的权，一概看不到。

### 修法（[VPackageManagerService.java](third/com/virtual/server/pm/VPackageManagerService.java)）
`checkPermission()` 的兜底换成和 `checkUidPermission()` 一致：查**宿主包
（dev.twinbox.app）的真实授权状态**（`getUnHookPackageManager().checkPermission()`）。
两条入口现在同源：容器里已声明过的权限仍维持「声明过即放行」的原语义，
未声明的（系统权限/第三方库权限）落到宿主真身授权上。

### 验证要点
- guest 里原先报「未授权」的入口（XPlayer 自己弹的权限引导、设置里的开关）应恢复；
- 若要进一步确认，日志里加一行 `permission granted from host` 之类即可——
- 如果还有 guest 认不到、但那两条入口都查过的权限，那就是第三条路径
  （AppOps/`isExternalStorageManager()`），对照日志里的 permission 名即可定位。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/server/pm/VPackageManagerService.java` | 2fabd627435651d66d9fc5f1d8d972b7c776e910ca304e7c346ea10992235491 | 两个重载的兜底都查宿主真身 |

## 2.1.37：「一直提示要授权」——Android 16 换了 IPC 方法名，权限检查整条没被钩住

### 现象
播放好了、2.1.35/2.1.36 也把两个权限查询重载改了，但 XPlayer 仍然一直提示要授权
（宿主演系统设置里明明已经授权）。

### 根因：Android 16 把 checkSelfPermission 的落地方法换了名
对着 `android-16.0.0_r1` 的源码把调用链走到底：

```
ContextImpl.checkSelfPermission(perm)
  → ContextImpl.checkPermission(perm, pid, uid)
  → PermissionManager.checkPermission(perm, pid, uid, deviceId)      ← 带缓存
  → checkPermissionUncached(...)
  → IActivityManager.checkPermissionForDevice(perm, pid, uid, deviceId)   ← 新方法！
```

容器 `am/MethodProxies` 里只有老的 `CheckPermission` / `CheckPermissionWithToken`
两个钩子；`checkPermissionForDevice` **没有任何钩子** → guest 的权限检查直接透传到
真 AMS，而 `Process.myUid()` 在 guest 里被容器 libcore hook 成 **vuid（如 10002）**
→ 系统查不到这个 uid 的包 → 永远 `PERMISSION_DENIED`。宿主授不授权、容器那两个
重载怎么改，都救不了这条完全没被接管的路。

### 修法（[am/MethodProxies.java](third/com/virtual/client/hook/proxies/am/MethodProxies.java)）
新增 `CheckPermissionForDevice` 钩子（嵌套静态类，`@Inject(MethodProxies.class)` 自动
注册到 IActivityManager 代理上），逻辑与 `CheckPermission` 一致：
- 读 `args[0..2]`（permission / pid / uid），deviceId 用 `indexOfLast(Integer)` 兜位置；
- 保留原来的 APN 设置特判；
- 之后走 `VActivityManager.checkPermission` → 虚拟 PM：已声明权限直接放行、
  未声明权限查宿主真身授权（2.1.35/2.1.36 的修法在这条路上生效）；
- 打一行诊断日志（`checkPermissionForDevice perm=... → virtual check`），
  下次一眼能看出这条钩子有没有被触发。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/proxies/am/MethodProxies.java` | fd7588381818350d380fe8266109b219b5f0bdfe3e0deecdd7b8a6285d2ab630 | 新增 checkPermissionForDevice 钩子 |

### 验证要点
- 日志出现 `checkPermissionForDevice perm=<X> ... -> virtual check`（每条 guest 权限
  检查都会过这个钩子）；
- XPlayer 的授权提示消失；若仍提示，核对 XPlayer 弹出的权限名，那可能是
  `getPermissionRequestState`（shouldShowRequestPermissionRationale 走的路，
  AppGlobals.getPermissionManager()）之类的第四条路径，同样思路补钩子。

## 2.1.42：数据密封加密（SealedStorage，容器内数据 at-rest 加密）

### 需求与威胁模型
root 文件管理器（MT Manager 一类）可以直接打开
`/data/data/dev.twinbox.app/virtual/data/user/0/<pkg>/` 看到容器内应用的**明文数据**。
目标是：guest 不运行时，落盘数据全部是密文；guest 运行时无感（正常读写明文）。

明确**不做**的事：不追求内核级透明加密（FUSE/dm-crypt 需要特权，普通 app 做不到），
不防「guest 运行中 + 对手有 root + attach 调试器」——那属于运行时内存对抗，不在
at-rest 模型内。

### 实现架构（[SealedStorage.java（新增）](third/com/virtual/server/seal/SealedStorage.java)）

**密文格式**（每个文件独立加密）：

```
[4B 魔数 "TBS1"][12B 随机 IV][AES-256-GCM 密文(尾随128bit认证tag)]
明文文件 foo.db → 密文 foo.db.tbs（同目录同名 + 后缀）
```

- **密钥**：AndroidKeyStore 别名 `twinbox_seal_v1`，AES-256-GCM，硬件背书
  （StrongBox/TEE，密钥材料不可导出），首次使用时 `KeyGenerator` 现场生成。
- **流式处理**：`CipherInputStream/CipherOutputStream` + 64KB 缓冲，不整文件读进
  内存；单文件 >256MB 跳过（防内存/时延爆炸，TLog 留痕）。
- **写序（防半成品）**：seal 先写 `<name>.tbs.tmp` → `rename` 成 `.tbs` →
  覆写零 + 删除明文。rename 是原子的，任意一步断电/被杀，磁盘上要么有完整
  `.tbs` 要么没有，不存在半截密文。

**生命周期挂载点**（三个时机，全部在引擎 `:x` 进程）：

| 时机 | 位置 | 动作 |
|---|---|---|
| guest 进程 spawn 前 | `VActivityManagerService.startProcessIfNeedLocked()`（`isLaunched` 判断后） | **同步** `unsealPackage(pkg, userId)`——必须在 initProcess 拉起 guest 之前：guest 一开进程就可能开 SQLite，文件没解封=空库假象 |
| 本包最后进程死后 | `VActivityManagerService.onProcessDied()` | `hasLiveProcess()` 判定无存活 → **异步** `sealPackageAsync()`（MIN_PRIORITY daemon 线程） |
| 引擎每次启动 | `BinderProvider` → `VActivityManagerService.systemReady()`（延迟 2s，`isServerProcess()` 守卫） | `sweepAll()` 扫尾：补封 o-stop 团灭/崩溃留下的明文残余；禁用开关存在时反向全解封 |

> 顺带修了个上游 bug：`VActivityManagerService.systemReady()` 此前**从未被任何
> 代码调用**（BinderProvider 接了其他服务的 systemReady，唯独漏了 AMS）——本版接线。

**目录覆盖**：`data/user/<id>/<pkg>`、`data_de/user_de/<id>/<pkg>` × 32/64 位
共四个变体（靠 VEnvironment 的目录探测 + 父目录枚举，不硬编码路径）；跳过
`cache/`、`code_cache/`（可再生数据不值得加密开销）；**APK 本体不加密**
（`virtual/app/`，程序文件不是数据）。

### 关键机制：竞态与崩溃一致性

**竞态闭合**（「密封中用户又点开了 guest」）：
- per-`(pkg,userId)` 锁表（`ConcurrentHashMap` + 双检）；
- unseal 持锁 → 放行 spawn；seal 工作线程拿同一把锁 → 拿到后**重查存活表**
  `hasLiveProcess()`，guest 已复活就放弃本次密封；
- guest 进程的唯一注册入口是 `startProcessIfNeedLocked`（`mPidsSelfLocked.add`
  仅此一处），所以「unseal 持锁放行的 spawn」与「seal 持锁的判定」在锁语义上
  互斥，不存在密封到一半进程起来的窗口。

**崩溃一致性**（任意方向中断都能收敛）：
| 残留态 | 收敛动作 |
|---|---|
| `.tbs` 与明文并存（seal 写完密文、还没擦明文就死了） | 下次 unseal：`.tbs` 解出**覆盖**明文，删 `.tbs` |
| 只有 `.tbs.tmp` / `.tbs.out`（写到一半） | 半成品不可信，sweep/unseal 直接删（真源还在） |
| 解封失败（密钥失效等） | 写 `broken` 标记 + **拒绝该目录后续 seal** |

**broken 标记的安全语义**：解不开（比如恢复出厂后 Keystore 钥匙没了）时，
宁可让用户**看到旧密文**，也不让「空目录 + 下次 seal 把空数据覆盖成新密文」
把真数据二次销毁。见到 broken 标记 = 该目录数据已不可恢复，需要人工评估。

### 注意问题（边界与已知限制，都会在日志里出现）

1. **运行时进程内是明文**：guest 跑着的时候，有 root+调试能力的对手可以读它内存。
   本特性挡的是「翻落盘文件」，不是「活体解剖」。
2. **恢复出厂 / 清除凭证 = 旧密文永久不可恢复**：Keystore 钥匙没了，数学上无解。
   重要数据请自行另有备份；新密钥生成后旧 `.tbs` 仍然解不开（broken 路径）。
3. **覆写≠物理抹除**：闪存磨损均衡下，zero-overwrite+delete 是 best-effort，
   取证级对手理论上可能从物理页残影恢复。威胁模型按「文件管理器级对手」设定。
4. **首验建议拿可重生的数据试**（XPlayer/纯音），别第一发就压重要数据——新特性
   v1，虽然崩溃一致性设计过，战场首验要给回滚留余地。
5. **首次 seal 有耗时**：大数据量目录密封要几秒到几十秒（64KB 流式 + MIN_PRIORITY），
   在后台线程跑不卡界面；期间杀宿主 = 残留态，下次启动 sweep 收敛。
6. **禁用开关（逃生门）**：宿主 `files/.twinbox_seal_off` 建空文件 → 功能整体
   禁用，且**下次引擎启动自动全量解封**现有 `.tbs`（root 文件管理器可建）。
   `isDisabled()` 判定失败时也按禁用处理（保守，不动用户数据）。
7. **不加密的东西**：APK 本体、cache/code_cache、>256MB 单文件（跳过+留痕）、
   虚拟存储（`virtual/vs/`，对应宿主外置卡视图）——注意虚拟存储目录里如果有
   敏感数据是**明文**的，属于已知边界。

### 日志锚点（TLog 标签 `V|SEAL`）
```
V|SEAL: seal <pkg>/<userId>: N files, Xms        ← 每次密封
V|SEAL: unseal <pkg>/<userId>: N files            ← 每次解封
V|SEAL: sweep seal <pkg>/<userId>: N files        ← 启动扫尾补封
V|SEAL: sweep(unseal-all, disabled): N files      ← 禁用开关反向解封
V|SEAL: seal skip (broken marker): <dir>          ← 数据已不可恢复，人工评估
V|SEAL: unseal fail ... broken marker written     ← 同上（触发时刻）
```

### 验证路径
1. 装 guest（建议先 XPlayer/纯音）→ 用一用 → **完全退出**（最近任务划掉）；
2. root 文件管理器进 `/data/data/dev.twinbox.app/virtual/data/user/0/<pkg>/`：
   应满眼 `.tbs` 后缀密文，打开是乱码（含 `TBS1` 头）；
3. 再点开 guest 正常使用：数据完好（unseal 在 spawn 前同步完成，用户无感）；
4. 反复「退出→看密文→再打开」几个循环，验证收敛逻辑；
5. 极端项（可选）：密封中途 force-stop 宿主，重启后看 sweep 日志与数据完整性。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/server/seal/SealedStorage.java` | c6c3ad2ccfce6daa8abb44dcde048465c4dc2d1548163b909dbfb8c2c79d7ddf | 新增：密封引擎全套（格式/密钥/遍历/锁表/收敛） |
| `third/com/virtual/server/am/VActivityManagerService.java` | 83d342c8b195a3143c6da33a0671993d2d11a327fffa441cc8354d238ca1c2bd | spawn 前解封 / 死后密封 / systemReady 扫尾 + `hasLiveProcess()` |
| `third/com/virtual/server/BinderProvider.java` | d9080cd330b740e3f586f0c7e32df7401ebec965cf65e51ff53275b4a9785813 | 接线 `VActivityManagerService.systemReady()`（上游漏调） |

版本号 `versionCode 68 → 69`，`versionName 2.1.41 → 2.1.42`。
（2.1.38-41 为 Android 16 权限/归属校验家族修复：`checkPermissionForDevice`、
createUser 分身缺失、LocaleManager 读写钩空 + uncaughtException 家族安全网，
详见 TLog 与源码内 TwinBox 注释，未单独立章。）

## 2.1.43：LocaleManager 崩到 binder 层接管——DeepSeek「Unknown package」FATAL

### 现象
2.1.42 build 装上之后，DeepSeek（`com.deepseek.chat`）一进 guest 就崩，
安卓崩溃通知栏直接弹「Java 崩溃」：

```
FATAL EXCEPTION: Thread-6
java.lang.IllegalArgumentException: Unknown package: com.deepseek.chat for user 0
  at android.app.ILocaleManager$Stub$Proxy.setApplicationLocales(ILocaleManager.java:219)
  at android.app.LocaleManager.setApplicationLocales(LocaleManager.java:111)
Caused by: android.os.RemoteException: Remote stack trace:
  at com.android.server.locales.LocaleManagerService.isPackageOwnedByCaller(LocaleManagerService.java:375)
  at com.android.server.locales.LocaleManagerService.setApplicationLocales(LocaleManagerService.java:252)
```

（同一份 crash 06_10-23-57 就出现过一次，当时的 2.1.40/2.1.41 属于按 context
打补丁，没盖住 DeepSeek 实际走的那条实例；本次 07_10-10-09-25 复现。）
同一台机器的另一份 logcat 里 2.1.37 的 `checkPermissionForDevice ... -> virtual check`
正常打印，XPlayer/纯音无异常——**只有新 guest 的语言初始化会踩**。

### 根因：per-app locale 是按「调用者 uid 是否拥有该包名」校验的
对着 `android-16.0.0_r1` 源码（`ILocaleManager.aidl` / `LocaleManagerService.java`）走一遍：

```java
// 服务端：LocaleManagerService.java:375
private boolean isPackageOwnedByCaller(String packageName, int userId) { ...
    throw new IllegalArgumentException("Unknown package: " + packageName + " for user " + userId);
}
// setApplicationLocales / getApplicationLocales 都先过这道校验
```

guest 进程里这些方法**带着 guest 包名打到真系统服务**，调用者 uid 却是宿主真身
（`dev.twinbox.app` uid 10041，ContextFixer/AttributionSanitizer 早就把身份换成宿主了）
——宿主当然不"拥有"`com.deepseek.chat` → 抛 `IllegalArgumentException`，而
`LocaleManager` 客户端没有 try/catch（`RemoteException` 都不兜，直接 rethrow）→
后台线程未捕获 → 进程死。

与 2.1.33 音频那次完全同构（guest 包名 + 宿主 uid 的组合泄漏给系统服务）：
音频那次是 AppOps `PLAY_AUDIO` 静音，这次直接 FATAL。
AIDL 上带这个 `packageName` 首参的共四个方法，**整条链都是同一道校验**：

| 方法（Android 16 签名） | 现状 |
|---|---|
| `setApplicationLocales(String pkg, int userId, LocaleList locales, boolean fromDelegate)` | DeepSeek 实测崩溃 |
| `getApplicationLocales(String pkg, int userId)` | 同校验，「读当前→比对→再 set」是常见模式，一样炸 |
| `setOverrideLocaleConfig(String pkg, int userId, LocaleConfig)` | 同校验，目前没有 guest 触发 |
| `getOverrideLocaleConfig(String pkg, int userId)` | 同上 |

注意 16 的 `setApplicationLocales` 是 **4 参**（userId 在 pkg 后、fromDelegate 垫尾，
13/14 曾经是 3 参）——钩子按方法名 + 首参包名取，不写下标，天然免疫。

### 修法：钩子上移到 binder 层（[LocaleManagerStub.java](third/com/virtual/client/hook/proxies/locale/LocaleManagerStub.java)）
沿用 `AlarmManagerStub` / `LocationManagerStub` 的既有形状：`BinderInvocationProxy`
+ `@Inject(MethodProxies.class)`，`InvocationStubManager` 在 vApp 进程按 API 33+ 注册。
- **`super.inject()`**：`replaceService("locale")` 把 `ServiceManager.sCache` 的
  条目换成钩子——Android 16 的 `SystemServiceRegistry` 仍是
  `new LocaleManager(ctx, ILocaleManager.Stub.asInterface(ServiceManager.getServiceOrThrow("locale")))`，
  所以**之后任何 context（含 `createConfigurationContext`/`createPackageContext`
  这些不经 fixContext 的）新建的 LocaleManager 都自带钩子**，这就是 2.1.40 漏掉的那类口子；
- **`patchCachedManagers()`**：顺手把宿主主 context 与 guest Application context
  已缓存实例的 `mService` 逐个换成代理（存量兜底，和 AlarmManager 同款）；
- **四个钩子**（[MethodProxies.java](third/com/virtual/client/hook/proxies/locale/MethodProxies.java)）：
  首参包名非宿主 → set 吞掉（返回值和真系统一样是 void，app 以为设置成功）、
  get 返回空 `LocaleList`（=「无 per-app 设置」，语言跟随系统）；
  宿主自己的调用（ContextFixer 已把 `mBasePackageName` 换成宿主，应用走无参 API
  时传的就是宿主包名）**原样透传**，不破坏宿主自身行为；
- 每一步都写 TLog（`V|Locale: setApplicationLocales(com.deepseek.chat) swallowed...`）。

第二层防护保留：[LocaleFixer.java](third/com/virtual/client/fixer/LocaleFixer.java)
（按 context 逐个 patch）改成只拦外来包名、四个方法全覆盖，兜「sCache 被换之前
就创建、又刚好走过 fixContext」的存量实例——主力已经是 binder 钩子。

### 为什么必须 binder 层
`LocaleManager` 由 `SystemServiceRegistry` 的 `CachedServiceFetcher` 创建，
**每个 `ContextImpl` 一份实例缓存**，`ContextFixer.fixContext` 只覆盖
Application / Activity / Service 三类 context。guest 从别的 context 拿到的
`LocaleManager` 没被 patch，`mService` 仍是真 binder —— 2.1.40/2.1.41 的
per-context 补丁结构上就盖不住这类路径，这也是「明明改了还崩」的原因。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/proxies/locale/LocaleManagerStub.java` | 8f8ae543b582036a48b7b6e4224b6d9a059a573a0e09f54776a7c36413e5f12d | 新增：binder 层接管 ILocaleManager |
| `third/com/virtual/client/hook/proxies/locale/MethodProxies.java` | 157108dc2c241d0fe55bff9196932c4bc8d63618596c6f443b81f1696c5e99f7 | 新增：四个所有权校验方法的钩子 |
| `third/mirror/android/app/ILocaleManager.java` | 12296e9b25fae55a95e533a96f5439e35fb530a7810b1da9f865f2b0bf7b3486 | 新增：隐藏接口 mirror（AIDL 签名核对） |
| `third/mirror/android/app/LocaleManager.java` | 278f313832acc1215ffd7f6e4a779dcfe9b1e767e6d55f1b6a114ac017c16be4 | 新增：manager 的 mService 字段 mirror |
| `third/com/virtual/client/core/InvocationStubManager.java` | 1ab13b5665d8151b3281bd496afa42d97774d1c1649c219bcc3d4159a93965e4 | vApp 进程注册 LocaleManagerStub（API 33+） |
| `third/com/virtual/client/fixer/LocaleFixer.java` | 72f3ed852ccee6acebe5a81c08e50fc09ce625a62180214968023c4e06d3ac64 | 第二层：只拦外来包名 + 四方法全覆盖 |

（`find third -name "*.java"` 全量编译，新目录自动进包；版本号照例由
`host-manifest-template.xml` 维护，建议随本版 bump 至 2.1.43。）

### 验证要点
- 装 DeepSeek 进容器启动：不再弹「Java 崩溃」通知，app 正常进主界面；
- 日志（`V|Locale`）出现 `setApplicationLocales(com.deepseek.chat) swallowed: no system identity
  in box, per-app locale unsupported`——说明 binder 钩子真的接到了这条 IPC；
- 宿主自身行为不受影响：TwinBox 主界面语言、XPlayer/纯音回归无变化；
- 若还有 guest 报 `Unknown package`：`V|Locale` 一行都没有 = 钩子没注册
  （看自检 DEAD 列表里有没有 locale 的方法名），有 swallowed 但还崩 = 另一条
  IPC 路径（取它调用的方法名，同构补钩子即可）。

### 验证记录（2026-10-07 12:04 真机 · DeepSeek 2.6.1 · SDK 36）
**通过。** `twinbox-20261007.log` 第 2 份（guest 进程）：

```
12:04:21.420 I/V|Locale: setApplicationLocales(com.deepseek.chat) swallowed: no system identity in box, per-app locale unsupported
12:04:21.517 I/V|Locale: getApplicationLocales(com.deepseek.chat) -> empty (no per-app locale in box)
12:04:21.326 I/V|VC: makeApplication OK: com.deepseek.chat.App — calling app onCreate...
12:04:21.194 I/KeepAlive: foreground keep-alive ON（guest 运行中，后台清理豁免）
12:04:39.068 I/KeepAlive: task removed by swipe, FGS survives (o-stop check)
```

- 读写两条路径都接到钩子，`Unknown package` FATAL 不再出现；
- 同一份 1.5MB logcat 里 uid 10041（guest 进程）**E 级日志 0 条**；
- 2.1.37 的 `checkPermissionForDevice perm=... -> virtual check` 同时正常打印
  （STATUS_BAR_SERVICE / INTERACT_ACROSS_USERS / READ_MEDIA_IMAGES 三条）；
- 2.1.38-41 的 `V|PH: fixAttr: 10005/com.deepseek.chat -> host(10041/dev.twinbox.app)`
  归属改写也正常；本版可收尾。

## 2.1.44：ENOENT 不再刷 E 级堆栈（hook 兜底日志降噪）

### 现象
每份 guest 日志里都有一大片「看起来像故障其实不是」的东西：

```
E/V|MethodInvocationStub: Hook crash, fallback to origin method: Os.stat
    args=[[/data/user/0/dev.twinbox.app/virtual/data/user/0/com.deepseek.chat/shared_prefs/__wx_opensdk_sp__.xml]]
    : java.lang.reflect.InvocationTargetException
  ... 13 行堆栈 ...
Caused by: android.system.ErrnoException: stat failed: ENOENT (No such file or directory)
```

DeepSeek 一次启动 8 条 × 15 行 = 120 行噪音，把真问题淹掉。

### 根因：ENOENT 是「探测文件在不在」的正常返回路径
`Os.stat / Os.lstat / Os.access` 探到路径不存在就抛 `ErrnoException(ENOENT)`，
`SharedPreferencesImpl.loadFromDisk`、`File.length()`、profile/dex 产物探测全靠
这个语义工作。而 2.1.17/2.1.19 的兜底网不区分「hook 自己写错了」和「系统正常
返回不存在」——一律 E 级 + 全堆栈。

### 修法（[MethodInvocationStub.java](third/com/virtual/client/hook/base/MethodInvocationStub.java)）
新增 `isExpectedNoFile(Throwable)`：剥掉 `InvocationTargetException` 后判断
`ErrnoException.errno == OsConstants.ENOENT`。命中就降级成一行 I 级日志
（含方法名和 args，够定位路径），**不再打堆栈**；其余异常照旧 E 级 + 全堆栈
（ClassCastException 一类真 bug 的诊断能力不变）。透传行为完全不变——
`hooked=false` 后照旧 `invokeOrigin()`，ENOENT 继续抛给调用方处理。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/base/MethodInvocationStub.java` | beeae3078093edf0e30f28e65b1ff85de73b48c6faa9de8ccbc1a1f12bae8536 | ENOENT 预期异常降级为 I 级一行 |

### 验证要点
- guest 启动后：`hook ENOENT (expected, file absent): Os.stat args=[[...]]` 一行一条，
  不再有 `Hook crash ... ENOENT` + 13 行堆栈；
- XPlayer/纯音回归无变化；真 hook 故障仍会以 E 级 + 堆栈出现。

## 2.1.45：点开容器内应用只有一个透明界面（launcher 被「首个 activity」兜底选错）

### 现象（07_10-12-39-50_170.log · Android 16 · DeepSeek 2.6.1 · 无崩溃）
容器里只有 DeepSeek 一个应用（`listInstalled: 1 apps in box`）。用户在容器桌面点了 DeepSeek 图标，
**不闪退、无 FATAL、无 ANR**，屏幕上只有一个透明界面（能看出是「空的透明页」）。
同一份 logcat 里三条决定性证据：

```
12:39:21.728 10041 1451 I TwinBox/VBox:   launch: com.deepseek.chat userId=0
12:39:21.730 ...  I TwinBox/V|Core:       getLaunchIntent q2(LAUNCHER) size=1
12:39:21.731 ...  I TwinBox/VBox:         query launcher:
                       ComponentInfo{com.deepseek.chat/com.deepseek.chat.wxapi.WXEntryActivity}
12:39:21.751 1000 ...  I ActivityTaskManager: START u0 {typ=com.deepseek.chat/
                       com.deepseek.chat.wxapi.WXEntryActivity ... cmp=dev.twinbox.app/
                       com.lody.virtual.client.stub.ShadowDialogActivity$P0 ...}
12:39:24.797 10041 13646 D TransparentWindowDetector:
                       Detect Empty window:com.deepseek.chat/com.deepseek.chat.wxapi.WXEntryActivity
```

即：容器把 **WeChat 回调页 `wxapi.WXEntryActivity` 当成了启动入口**（那是透明的授权回调页，
正常流程要等微信回跳才会 finish）。它自己不会拉起主界面，所以「点开只有透明界面」，
系统 UI 甚至自己打了 `Detect Empty window`。

### 根因：launcher 判定在 Android 12+ 只剩「首个 activity」这一条路
`PackageParserEx.parsePackage()` 在 SDK ≥ 31 上**一律**走 `parsePackageModern()`
（公开 API 桥 `getPackageArchiveInfo`）。这条路**拿不到任何 intent-filter**，
所以容器里那个 `MAIN + LAUNCHER` 假 filter 只能人工挂，而老代码挂的位置是：

```java
// 2.1.44 及以前
String launcherClass = null;
try {  // 只有一级：宿主装了同包 → 借宿主 PM 反查
    Intent li = pm.getLaunchIntentForPackage(pi.packageName);
    if (li != null && li.getComponent() != null) launcherClass = li.getComponent().getClassName();
} catch (Throwable ignore) {}
...
if (!launcherSet && (info.name.equals(launcherClass) || launcherClass == null)) { /* 挂 filter */ }
```

从 `getLaunchIntent q2(LAUNCHER) size=1` + 命中 `WXEntryActivity` 可以反推：容器里那个假
filter 挂在 **APK 里第一个 activity** 上（真 launcher 绝不会是 WeChat 回调页）。走到这一步有
两条路，本版一并覆盖：

1. **宿主没有同包**（SAF 装进容器的 APK 基本都是这种）→ `launcherClass == null`，
   循环条件里的 `|| launcherClass == null` 直接把入口给了第一个 activity；
2. 宿主有同包但**版本不同 / 类名对不上**，或 `getLaunchIntentForPackage` 抛异常、返回 null
   → 循环里一个都没匹配上 → 末尾的 `if (!launcherSet) { … cache.activities.get(0) }`
   同样把入口给第一个 activity。

于是启动链、桌面点击、`isPackageLaunchable()` 全部指向这个透明回调页。受影响的是
**所有「APK 里第一个 activity 不是主界面」的应用**，不针对 DeepSeek（它有 11 个 activity，
容器只认得这一个 launcher）。

### 修法

| # | 文件 | 改动 |
|---|---|---|
| 1 | [ApkManifestLauncher.java（新增）](third/com/virtual/helper/utils/ApkManifestLauncher.java) | 直接读**容器 APK 自己的 AndroidManifest.xml** 找 `MAIN + LAUNCHER`：隐藏 API `AssetManager.addAssetPath(apk)` + `openXmlResourceParser("AndroidManifest.xml")` 借框架自己的二进制 XML 解析器解码（不自写 axml 解析），单趟扫 `activity` / `activity-alias` → `intent-filter` → `action/category`；`activity-alias` 返回 `targetActivity`（容器 VPackage 里只有真 activity 的 ActivityInfo，alias 启动不了）。任何一步失败只记日志返回 null |
| 2 | [PackageParserEx.java](third/com/virtual/server/pm/parser/PackageParserEx.java) | launcher 判定改三级：**APK manifest（权威）→ 宿主 PM 反查（老逻辑，保留）→ 名字启发式**（MainActivity / LauncherActivity / SplashActivity …，优先非 Translucent 主题）；三级全落空才退回首个 activity，并且**打 W 级日志留痕**（`launcher fallback to FIRST activity: <类名> … 若点开是透明/空界面，就是这个入口选错了`），不再静默挂错 |
| 3 | [VBox.java](src-host/dev/twinbox/app/VBox.java) | `launchInner()` 起链前用 `ApkManifestLauncher` 拿 APK manifest 的入口**校正一次**（`fixLauncherFromApk`）：容器里已装好的旧数据（VPackage 缓存是安装时写的）**不用卸载重装**就能立刻修好；只有「manifest 扫到的入口 ≠ 容器解析出的入口」且「该 activity 在容器 VPackage 里确实存在（`VPackageManager.getActivityInfo` 非 null）」才改，其余原样返回，日志 `launcher corrected by APK manifest: A -> B` |

### 验证要点
- 点容器桌面上的 DeepSeek：直接进主界面（不再是透明页）；
- TLog / logcat 出现
  `V|ApkManifestLauncher: launcher from manifest: com.deepseek.chat.<真入口>`
  以及 `TwinBox/VBox: launcher corrected by APK manifest: com.deepseek.chat.wxapi.WXEntryActivity -> com.deepseek.chat.<真入口>`；
- 不再出现 `Detect Empty window:...WXEntryActivity`；
- 新装/重装的 APK 也会走同一条路：解析期就会把假 launcher filter 挂在真入口上，
  TLog 里 `modern parse ok: <pkg> launcher=<真入口>`；
- 万一某台机器上 manifest 扫描被拦（日志 `addAssetPath returned 0` / `scan manifest fail`），
  行为退回本版之前 + 一条 W 级提示，不会比现在更差；
- **反例要认出来**：日志若出现
  `V|PackageParserEx: manifest launcher theme is Translucent: <类名>`，说明这份 APK 的 manifest
  **本身**就把透明页声明成了入口（常见于第三方魔改/重打包的 APK），本补丁不会去纠正它——
  这种情况在容器外也是一样的表现，需要单独评估，不要再当容器 bug 查一轮；
- 回归：克隆的主空间应用（宿主有同包）入口不变（manifest 与宿主 PM 给出的入口一致）。

### 顺带记一笔（同一根因的更大面，本轮未动）
`parsePackageModern()` 丢掉的是**所有**组件的 intent-filter，不只是 launcher 这一条：
guest 在容器内做隐式 intent 解析（自己包的 `queryIntentActivities`、`<receiver>`/`<service>` 按
action 匹配、deep link）同样拿不到结果 —— 这些是「虚拟化缺口」（功能不生效），不会崩。
要一起补的话，`ApkManifestLauncher` 的 XML 扫描已经是现成的地基：把 activity/receiver/service
的 `<intent-filter>` 一并扫出来填进 `VPackage` 的 `ActivityIntentInfo`/`ServiceIntentInfo`
即可。下一版需要时再做，避免这版一次改太多。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/helper/utils/ApkManifestLauncher.java` | 9b8b0818a228b0c7726e1828cc41f520e853585ebd6b002e54c22c4c05035d77 | 新增：从 APK manifest 读真 launcher |
| `third/com/virtual/server/pm/parser/PackageParserEx.java` | 2b3b421c0fc56178d4fd196edf671fe139815d147768fe3800852da7a88a6ff4 | launcher 三级判定 + 挂错时 W 级留痕 |
| `src-host/dev/twinbox/app/VBox.java` | a3510cf11e45578e2b6b19daa1942ebd5163d021cce4cc8868bbadc01de43a61 | 启动前用 APK manifest 校正入口（旧数据免重装） |

版本号由 `host-manifest-template.xml` 维护（当前 `versionCode=71 / versionName=2.1.44`，出包时 +1 至 2.1.45）。

### 验证记录（2026-10-07 13:06 真机 · DeepSeek 2.6.1 · SDK 36）：**通过**
```
13:06:45.531 I/V|Core: getLaunchIntent q2(LAUNCHER) size=1
13:06:45.532 I/VBox:   query launcher: ComponentInfo{com.deepseek.chat/com.deepseek.chat.MainActivity}
13:06:45.533 I/V|ApkManifestLauncher: launcher from manifest: com.deepseek.chat.MainActivity
13:06:45.888 I/VBox:   VActivityManager.startActivity result=0 component=...deepseek.chat.MainActivity
```
透明界面消失、启动链直进 `com.deepseek.chat.MainActivity`（不再是 `wxapi.WXEntryActivity`），
`TransparentWindowDetector: Detect Empty window` 不再出现。
**同一份 TLog 里暴露出下一个问题**（与本次改动无关，是 2.1.42 密封器引入的）：进主界面 1.4s 后
guest 原生崩溃，见 2.1.46。

## 2.1.46：密封器把 guest 的原生库当数据擦了 —— 主界面 1.4s 后 SIGILL（libmmkv.so）

### 现象（crash-com-deepseek-chat-07_10-13-06-47_524.log，2.1.45 装机后首测）
2.1.45 修好入口之后 DeepSeek 真的进了主界面，然后**原生崩溃**（Java 兜底网接不住）：

```
F libc  : Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x723e2b0bb4
          in tid 12625 (m.deepseek.chat), pid 12625
F DEBUG : Process uptime: 3s      Cmdline: com.deepseek.chat
F DEBUG : #00 pc 0000000000019bb4
          /data/user/0/dev.twinbox.app/virtual/data/app/com.deepseek.chat/lib/libmmkv.so (deleted)
F DEBUG : #03 pc ... (com.tencent.mmkv.MMKV.h+12)
```

两个关键词：**`SIGILL ILL_ILLOPC`**（执行到非法指令，ARM64 上全 0 字节就是 `udf #0`）+
**`(deleted)`**（崩的时候这个 .so 已经被 unlink，但进程还映射着它）。
容器侧 TLog 同一时刻的行（twinbox-20261007.log 三个进程段）：

```
13:06:45.771 I/V|SEAL: unseal com.deepseek.chat/0: 15 files, 236ms     ← spawn 前解封
13:06:45.878 I/V|VAMS: initProcess OK: com.deepseek.chat pid=12625
13:06:47.239 F libc : Fatal signal 4 (SIGILL) ... libmmkv.so (deleted) ← 崩
13:06:47.626 I/V|SEAL: seal com.deepseek.chat/0: 3 files, 37ms         ← 死后密封
13:06:48.056 I/V|SEAL: sweep seal 0/0: 29 files                        ← 引擎启动扫尾（身份印错了）
```

### 根因：`sealTree` 跟着 guest 的 `lib` 软链走进原生库目录，把正在执行的 .so 擦了

1. **`data/user/<id>/<pkg>/lib` 是指向 `data/app/<pkg>/lib` 的软链**：
   [VClient.java](third/com/virtual/client/VClient.java#L916-L926) 每次 `bindApplication` 都保证
   `getUserAppLibDirectory(userId, pkg)` 是指向 `libPath`（app 库目录）的软链
   （`FileUtils.createSymlink` 对目录走 `ln -s`）；`deletePackageDataAsUser(..., linkLib=true)` 同理。
2. **密封器按用户数据目录整棵树遍历，只跳过 `cache`/`code_cache`**：
   [SealedStorage.sealTree()](third/com/virtual/server/seal/SealedStorage.java#L403) 里只有
   `f.isDirectory()` 判断，**没有任何软链判断**，`SKIP_DIRS` 里也没有 `lib`
   → `data/user/0/com.deepseek.chat/lib/libmmkv.so` 被当成用户数据
   「AES 加密 → `.tbs` → 擦除明文」。
3. **擦除是从偏移 0 覆写零**（`wipe()`：`RandomAccessFile` 写 0 → `delete()`）：
   活进程里已经 mmap 的 .so 一旦被这样覆写，执行到那一页就是 `udf #0`
   → **SIGILL ILL_ILLOPC**；随后 `delete()` 让 tombstone 显示 `(deleted)`。
   两个现象同时对上，不是巧合。
4. **擦的是活进程**：`sweepAll()`（引擎启动 +2s）用目录名反推「包名/userId」，
   而 [allUserDirs()](third/com/virtual/server/seal/SealedStorage.java#L317) 老实现取 probe
   `.../user/0/0` 的**父目录**再枚举子目录 —— 返回的其实是**包目录**而不是 user 目录，
   身份解析全是垃圾：日志里那条 `sweep seal 0/0: 29 files` 就是它（**29 = 15 个数据文件
   + 14 个原生库**，正好是 spawn 解封的那 15 个再加上被软链带进 lib 目录的那些）。
   身份既然错了，`hasLiveProcess(pkg,userId)` 与 per-pkg 锁都落在错的 key 上
   → 对一个**正在运行**的 guest 动了手。

一句话：**程序文件（native .so）被当成了用户数据**，而「密封」是「原地写零 + 删除」，
不能施加于任何可能被 mmap 执行的文件。

### 修法（全部在 [SealedStorage.java](third/com/virtual/server/seal/SealedStorage.java)）

| # | 改动 | 说明 |
|---|---|---|
| 1 | **默认关闭**：`isDisabled()` 改成「只有宿主 `files/.twinbox_seal_on` 存在才启用」，老的 `.twinbox_seal_off` 仍可强制关闭 | 遍历没被彻底证明之前，先不给它碰用户数据的机会。要试用就建 `.twinbox_seal_on` |
| 2 | **密封时一律不跟软链**（`isSymlinkSafe`，判不出来按软链算），软链文件也跳过 | 软链目录直接跳过并留日志；配合第 3 条，`lib` 这条通路彻底封死 |
| 3 | **程序文件目录/后缀不密封**：`SKIP_DIRS` 增加 `lib`/`oat`/`dalvik-cache`；后缀 `.so/.apk/.dex/.oat/.vdex/.art` 一律跳过 | 程序文件不是用户数据，且随时可能被 mmap 执行 |
| 4 | **每个文件擦前重查存活**：`sealTree` 带 `(pkg,userId)`，写零之前再查一次 `hasLiveProcess`，一旦复活立刻收工并留日志 | 入口查一次不够——遍历可能跑几秒 |
| 5 | **`allUserDirs()` 修正**：从四个 user 根（`user`/`user64`/`user_de`/`user_de64`）显式枚举 userId，不再枚举包目录 | 身份解析不再错位（`sweep seal 0/0` 这类日志不该再出现） |
| 6 | **扫尾只补封真实安装过的包**：`PackageCacheManager.getSetting(pkg) != null` 才处理（纯内存查，不走 binder） | 历史残留/探针垃圾目录不再被当成包去 seal |
| 7 | **解封不受开关影响、且故意跟软链**（`unsealTree` 保持原样） | 已密封的数据必须能回来：2.1.42-2.1.45 误封的 `.so` 密文就躺在 `data/app/<pkg>/lib/`，只能从 `lib` 软链走进去解封；解封是「写回明文 + 删密文」，不是破坏性操作 |

### 影响面与恢复
- **数据本身没丢**：`sealFile` 的写序是「先写完整 `.tbs`，再擦明文」，被误封的文件都有密文在，
  解封即可还原；`broken` 标记（密钥丢失）路径不受影响。
- 装上新版后**引擎启动那次 disabled 分支的全量解封会自动还原**（`sweep(unseal-all, disabled)`）；
  若某个库仍缺，把容器里的 DeepSeek 卸载重装一次（lib 由安装流程重新解出）。
- 想立刻止血又不想重装 APK：用 root 文件管理器建空文件
  `/data/data/dev.twinbox.app/files/.twinbox_seal_off`，下次引擎启动即全量解封并停止密封。
- 代价（诚实记录）：密封默认关闭期间，容器内数据的**静止态加密不再生效**；
  需要它就在验证充分后建 `.twinbox_seal_on` 重新启用，并重点回归「guest 运行中不被动文件」。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/server/seal/SealedStorage.java` | b977f23ba1c7d139bee798dab3e13cf58ec82c112ed615338521e44ec7ae864a | 默认关闭 + 不跟软链 + 不碰程序文件 + 擦前重查存活 + allUserDirs/包表修正 |

版本号由 `host-manifest-template.xml` 维护（当前 `versionCode=71 / versionName=2.1.44`；
2.1.45 + 2.1.46 一起出包，直接 bump 到 `2.1.46`）。

## 2.1.47：并行线合流（剪贴板降 D + Autofill/输入法卡死修复）

> **合流说明**：此版之前的 2.1.45/2.1.46 存在**两条并行线撞号**——
> 线 A（launcher 修复 → 密封器修复，即上文 2.1.45/2.1.46 两章）与线 B
> （剪贴板降噪 → Autofill/输入法修复，出自基于 2.1.44 的并行工作目录，自标号也是
> 2.1.45/2.1.46）。**两个 2.1.46 各改各的文件、零交集**，故全部并入同一基线，
> 出包统一为 **2.1.47 / versionCode 74**。下两章按线 B 原文收录，版本号已改。

### A. 剪贴板轮询降到 D 级（最后一类 guest E 级噪音）

**现象**：DeepSeek 这类带输入框的 guest 每 5 秒轮询一次剪贴板，`ClipBoardStub`
把每次查询都按 E 级打（13:50 日志里刷了 10+ 条）。一次会话几十条，看着像故障，
实际是正常读路径（没有剪贴板就返回 false）。

**修法（[ClipBoardStub.java](third/com/virtual/client/hook/proxies/clipboard/ClipBoardStub.java)）**：
9 处 `VLog.e` 全改 `VLog.d`——**只改日志级别，逻辑与返回值一行没动**。
查询类（getPrimaryClip / hasPrimaryClip / hasClipboardText / getPrimaryClipDescription /
add|removeListener）本来就不值得 E 级；真要看还有 D 级在（TLog 文件与 logcat 都收 D）。

**验证要点**：DeepSeek 停在输入页几分钟，不再有 `E/V|ClipBoardStub` 行；
复制/粘贴功能本身不变（只动日志级别）。

### B. 点输入框卡死——guest 包名漏进真实 Autofill 服务，主线程同步等 5 秒

**现象**（07_10-13-50 真机 · DeepSeek 2.6.1 · SDK 36）：点输入框 → 键盘不弹 →
整个界面卡住。system_server 侧：`W Binder: Caught a RuntimeException ... rethrow` +
`PackageManager$NameNotFoundException: com.deepseek.chat`；guest 侧（主线程 tid==pid）：
`W AutofillManager: Exception getting result from SyncResultReceiver:
TimeoutException: Not called in 5000ms`。

**因果链**（对着 android-16.0.0_r1 源码逐行核对）：
1. `AutofillManager.startSessionLocked()` 把 `ComponentName clientActivity`
   （包名是 guest）随 `mService.startSession(...)` 一起 IPC 出去，然后用
   `receiver.getIntResult()` **同步等结果，超时 5 秒**（SYNC_CALLS_TIMEOUT_MS），
   跑在 guest 主线程；
2. 服务端第一件事就是 `getPackageInfoAsUser(clientActivity.getPackageName())`
   ——容器里根本没有 com.deepseek.chat 这个真包 →
   `IllegalArgumentException: ... is not a valid package`；
3. 整个 IAutoFillManager 是 **oneway** 接口，异常只停留在服务端，客户端的
   IResultReceiver 永远收不到回调 → 主线程干等满 5 秒；IME show 请求在同一时刻
   疯狂重试（一条日志里 13 次 onRequestShow/onCancelled），表现为「键盘调不出 +
   界面卡住」。

带包名身份的方法一共四个，同一条校验：`addClient`（老代码没钩）、`startSession`
（钩了但没生效）、`updateOrRestartSession`（钩了）、`isServiceEnabled`（钩了）。

**修法一：autofill 钩子重写（[AutoFillManagerStub.java](third/com/virtual/client/hook/proxies/view/AutoFillManagerStub.java)）**
- **注册时机提前**：`onBindMethods()` 里 `setDefaultMethodProxy`——任何遗漏的方法
  落到 `SanitizePackageArgs` 兜底（stripPkg 风格改写 + normalize userId），
  依赖 `MethodInvocationStub.getMethodProxy` miss → `mDefaultProxy` 的既有机制；
- **四方法全覆盖**：`startSession` / `updateOrRestartSession` / `isServiceEnabled`
  三个继承 `ReplaceLastPkgMethodProxy` 家族；`addClient` 用 `ReplacePkgAndComponentProxy`
  （ComponentName 与 String 尾参双改写）。

**修法二：Android 16 输入法静态缓存（[InputMethodManagerStub.java](third/com/virtual/client/hook/proxies/input/InputMethodManagerStub.java)）**
Android 16 起 `InputMethodManager` 不再持有 `mService`，所有输入 IPC 改走隐藏类
`IInputMethodManagerGlobalInvoker` 的进程级静态字段 `sServiceCache`（第一次输入
IPC 时解析一次就固定）。容器在 input_method 的 sCache 换 binder 只对「之后新建的
IMM 实例」有效；静态缓存一旦被真 binder 占住，IMMS 钩子整条失联。`inject()` 里
顺手把它也换成代理（拿不到字段就只靠 sCache，不致命），日志锚点
`V|IMMS: invoker sServiceCache -> proxy`。

**验证要点**：
- 点输入框：**不再卡 5 秒**，键盘正常弹出；
- `V|Autofill` 出现 `startSession: component com.deepseek.chat -> dev.twinbox.app`；
- system_server 不再有 `... is not a valid package` 那段 W Binder；
- `V|IMMS: invoker sServiceCache -> proxy` 出现（IMMS 钩子确认接上）；
- 自检行里 autofill 那个 stub 从 1 registered 变 **5 registered**。

需要同步的文件：

| 文件 | sha256 | 说明 |
|---|---|---|
| `third/com/virtual/client/hook/proxies/clipboard/ClipBoardStub.java` | 9a14348ce199f0ffe2b8a7620b69054091997e4af6da9daead88a3ac82d4a520 | 剪贴板查询日志 E → D |
| `third/com/virtual/client/hook/proxies/view/AutoFillManagerStub.java` | 95940013f7cdebf3ac513ed334c7277eec4522d79ff47d96ef53ddc3a597c029 | 钩子改构造期注册 + 补 addClient + 兜底默认代理 + userId 归一 |
| `third/com/virtual/client/hook/proxies/input/InputMethodManagerStub.java` | a1ad4c6e94ebcbf6dc77c79af12c74c26d98524510ceb8e17b569eacaa5ab776 | inject 时同步 Android 16 的 invoker 静态缓存 |
| `third/mirror/android/view/inputmethod/IInputMethodManagerGlobalInvoker.java` | 21996a2c0ca2b31cf65fb68b845f4c07524e5543f58ce224d71e6d4855825ac0 | 新增：隐藏类静态字段 mirror |

> **同步注意（线 B 基线分叉）**：线 B 工作目录基于 2.1.44，**不含**线 A 的
> 2.1.45（launcher：`ApkManifestLauncher` / `PackageParserEx` / `VBox`）与
> 2.1.46（密封器：`SealedStorage`）；`LocaleFixer` 是含编译错的旧版
> （`TLog.w` 无 Throwable 重载）。以本 README 上文对应章节的 sha256 为准。


## 2.1.48：TwinBox Design——宿主 UI 全面重制（MD3 形状语言，零新依赖）

### 背景与选型
宿主要求引入 UI 库美化界面。直构链（aapt2+ECJ+d8，无 Gradle）下
**标准 AAR 不可用**（AAR 内联资源 ID 与外链冲突，属构建体系级限制），
Material Components 源码移植成本为千级文件 + appcompat 主题基座替换。
采用第三条路：**自建设计令牌层**——MD3 的 token 体系 + shape/ripple/vector
全部原生 XML，观感到位且构建链纹丝不动。配色沿用「星云蓝」方案
（蓝 #5B8CFF / 紫 #8F6BFF / 深空底），只做 token 化精修。

### 落地范围（全部在 res/，Java 零改动——ID 全部沿用）
| 层 | 文件 | 内容 |
|---|---|---|
| 令牌 | `values/tokens.xml`（新增） | 色板（surface×4/primary/secondary/文字×3/描边/涟漪）+ 字阶 6 级 + 圆角 5 档 + 间距 6 档 + 控件尺寸 |
| 形状 | `drawable/` 新增 9 + 重写 2 | 卡片底/卡片涟漪/面板底（lg 圆角+描边）/单元格局部涟漪/胶囊×4（主/次/弱/文字级）/悬浮球容器/矢量图标×2（四宫格/X） |
| 布局 | 6 个全部重制 | 容器桌面（底部操作栏：双胶囊+圆图标钮）/ 应用项（全胶囊角标）/ 安装中心（提示进卡片+进度条着色）/ 安装列表项（卡片涟漪+胶囊操作位）/ 悬浮面板 / 悬浮球（58dp 容器+描边环，触达≥48dp） |
| 主题 | `themes.xml` | 接 `colorControlHighlight`（星云蓝涟漪），ActionBar 结构不变（2.0.8 语义保留） |

### 注意问题
- **btn_float 状态语义不变**：selected=悬浮球运行中（主色实底）——FloatingService
  的 setSelected 调用零改动；
- **悬浮球尺寸 46→58dp**：listener 挂根视图、尺寸来自 WindowManager.LayoutParams
  （WRAP_CONTENT），拖拽判定不受影响；触摸区变大反而更好用；
- **vector 图标**（ic_grid_apps/ic_close）依赖 minSdk≥21（本项目 24），
  aapt2 原生编译，无兼容库依赖；
- strings.xml 补 `btn_back`/`btn_pick_apk`（原为布局内硬编码字面值）。

### 验证
- aapt2 资源链接 0 error（首轮抓出 btn_pick_apk 缺失后补齐）；
- 布局 ID 全量比对脚本通过（6 布局 × 全部旧 ID 在位，Java 引用零改动）；
- 字节级 15/15：新 drawable/矢量/布局全部编入，arsc 含 md_* 令牌，
  引擎侧四大修复（密封器/launcher/locale/autofill）回归无损；
- 签名验证通过。真机观感与触感（涟漪/按压态）待实机确认。

需要同步的文件：`res/` 全目录（tokens/drawable/layouts/strings/themes）+
`host-manifest-template.xml`（75 / 2.1.48）。Java 引擎层无改动。


## 2.1.49：桌面布局精简（真机反馈三连）+ 空态直选 APK

### 用户反馈（真机截图实测）
1. 空容器时「安装 APK」出现两次（空态中央 + 底部操作栏），重复；
2. 顶部 ActionBar 的「安装应用」「悬浮球」与底部操作栏重复，各多一个；
3. 空态「安装 APK」点击后先跳「克隆应用」界面（InstallActivity）才弹选包——多一次界面切换。

### 修法
| # | 改动 | 文件 |
|---|---|---|
| 1 | **底部操作栏与空态互斥**：空容器时整条隐藏（装 APK / 克隆 / 悬浮球），只留中央空态按钮；装进应用后回来 | `activity_main.xml`（LinearLayout 加 `bottom_bar` ID）+ `MainActivity.reload()`（`mBottomBar.setVisibility`） |
| 2 | **溢出菜单只留「结束全部」**：删 menu_install / menu_float（与底部重复） | `menu_main.xml` + `MainActivity.onOptionsItemSelected`（同步删死分支，避免 R.id 悬空编译错） |
| 3 | **空态按钮定宽居中**：wrap→200dp（用户要求「加长居中」语义） | `activity_main.xml` |
| 4 | **直选 APK**：空态与底部「装 APK」都直接 `ACTION_GET_CONTENT` 拉起选包器；选完带 `EXTRA_INSTALL_URI` 跳 InstallActivity 直接 `installFromUri`（安装/进度/Toast/刷新逻辑零重复，且不再加载主空间列表） | `MainActivity`（pickApk + onActivityResult）+ `InstallActivity`（Uri 分支前置，跳过 reloadHostApps） |

### 注意问题
- menu 资源删除后 Java 侧 `R.id.menu_install` 引用必须同步清理（ECJ 直接报错，本次已处理）；
- strings.xml 同步删掉两个死 string，`menu_install`/`menu_float` 在 dex/arsc 均无残留；
- `EXTRA_AUTO_PICK` 老机制保留（后向兼容，当前无调用方）；
- 主页 `REQ_PICK_APK=42`（InstallActivity 用 41，互不冲突）。

### 验证
7/7：菜单双项彻底移除（dex+arsc）、kill_all 保留、底部栏互斥逻辑编入、
直选包链路（INSTALL_URI）编入、引擎四大修复（密封器/launcher/locale/autofill）回归无损。

需要同步的文件：`activity_main.xml` / `menu_main.xml` / `strings.xml` /
`MainActivity.java` / `InstallActivity.java` + manifest（76 / 2.1.49）。


## 2.1.50：容器内卸载不删数据（纯 64 位 guest 的数据目录无人删）

### 现象（用户实测）
APK 直装容器（物理机未装），容器内卸载后重装——**旧数据还在**（登录态/设置全保留）。
正常安卓卸载语义 = 数据连根删除，容器必须对齐。

### 根因（三个缺陷叠加）
1. **位宽门控删错对象**：`VAppManagerService.deletePackageDataAsUser()` 的数据目录删除
   被 `isPackageSupport32Bit(ps)` 门控。纯 64 位应用（无 32 位 ABI 的现代 APK）
   `flag=FLAG_RUN_64BIT` → 32 位块整跳过；
2. **单引擎下 64 位路径是空中楼阁**：TwinBox 只有一个宿主 `dev.twinbox.app`，
   64 位数据根 ROOT64 = `/data/data/dev.twinbox.app64/virtual`（**不存在的第二宿主目录**），
   guest 数据无论位宽都实际落在 32 位路径 `virtual/data/user/<id>/<pkg>`
   （SealedStorage 时代的 sweep 日志是铁证）。64 位桥删除删的是不存在的目录；
3. **V64BitHelper 路由 bug（上游手滑）**：`call()` 里 `METHODS[5]`（"uninstallPackage"）
   比较了两次，`METHODS[6]`（"cleanPackageData"）**永远路由不到**，静默返回 null。

三重叠加：纯 64 位 guest 卸载时，实际数据所在目录**没有任何代码去删**。

### 修法（[VAppManagerService.java](third/com/virtual/server/pm/VAppManagerService.java) + [V64BitHelper.java](third/com/virtual/server/bit64/V64BitHelper.java)）
| # | 改动 | 说明 |
|---|---|---|
| 1 | `deletePackageDataAsUser` 去位宽门控 | 数据目录删除一律执行：`user/<id>` + **`user_de`（上游连 32 位都没删过 DE 变体，一并补上）** + 两个 64 位路径变体（幂等，防未来双引擎）。位宽只决定 native lib 位置，不该决定数据删不删 |
| 2 | `uninstallPackageFully` 去位宽门控 | APK 资源/data-app/odex 目录删除同样无条件 |
| 3 | `V64BitHelper.call` 路由修复 | `METHODS[5]` 重复比较 → 第二处改 `METHODS[6]`，cleanPackageData 恢复可达 |
| 4 | 顺手修上游 bug | `userId==-1` 循环里 lib 软链重建用 `userId`（=-1）建目录是错的 → 改用循环变量 `info.id` |

多开语义保护：`uninstallPackageAsUser` 在多分身时只删该 userId 的数据（该语义原样保留）。

### 注意问题
- **从此卸载 = 数据彻底删除（不可恢复）**。本修复生效前的旧数据在下次卸载该应用时会被清掉——重要数据先在 guest 内自行备份；
- root 文件管理器路径核对：卸载后 `virtual/data/user/0/<pkg>/`、`virtual/data/user_de/0/<pkg>/`、`virtual/data/app/<pkg>/`、`virtual/vs/<pkg>~外部存储数据` 应全部消失。

### 验证
9/9：DE/64 变体删除方法编入、cleanPackageData64 路由可达、卸载调用栈日志（2.1.5 幽灵卸载定位器）回归、
密封器/launcher/UI 直选/autofill 回归无损。真机复现路径：装 → 产生数据 → 容器内卸载 →
root 文件管理器核对目录消失 → 重装 → 应为全新状态。

需要同步的文件：`VAppManagerService.java` / `V64BitHelper.java` + manifest（77 / 2.1.50）。


## 2.1.51：设备信息伪装（按分身粒度，宿主 UI 接通引擎身份池）

### 背景
用户问「容器里的 APK 读的手机信息是容器给的吗」——审计结论：默认**不是**。
引擎（VirtualApp 系）与 VMOS 类虚拟机的本质区别：guest 跑在宿主真实 UID 下，
binder 直达真系统服务，电话/AndroidId/MAC/传感器等默认全是**真机真值**，
容器只做「身份翻译」（包名/UID 改写，否则归属校验崩）。
引擎里躺着完整的假身份池（VDeviceConfig：随机 IMEI/AndroidId/WiFi+蓝牙 MAC/
ICCId/Serial/GMS 广告 ID，per-userId 持久化），但 enable 默认 false 且
宿主无任何开关——用户确认需要，本版接通。

### 设计决策：按分身（userId）粒度
身份池本来就是 per-userId 的（多开防关联的正确粒度：一个分身一套身份）。
全局开关意义有限（要开就每个分身各自开），故不做全局项。

### 实现（引擎侧全部现成，本版只做接线）
| 层 | 改动 | 说明 |
|---|---|---|
| AIDL | `IDeviceManager.aidl` 加 `getFakeDeviceState(userId)` | 轻量状态查询（避免 UI 为一个布尔拉整个 config） |
| 服务端 | `VDeviceManagerService.getFakeDeviceState()` | "on"/"off"（config.enable 派生） |
| 客户端 | `VDeviceManager.getFakeDeviceState()` | 引擎不可达按 off（保守：不虚报开启） |
| 宿主门面 | `VBox.fakeDeviceState/setFakeDevice` | +TLog 留痕 |
| UI | 长按菜单第 4 项「设备伪装」 | 单分身直接切换；多分身先选（列表项带当前状态：伪装中/读真机）；Toast 提示生效时机 |

### 生效范围（enable 开启后，guest 侧钩子清单——引擎既有）
- IMEI/MEID（getDeviceId/getImeiForSlot/getMeidForSlot）、IMSI、SimSerial、
  Line1Number（telephony + phonesubinfo 两族钩子）
- ANDROID_ID：SettingsProviderHook（call 拦截，enable+androidId 双条件）
- Wi-Fi MAC：NativeEngine.redirectFile 把 /sys/class/net/*/address 重定向到假值文件
- 蓝牙 MAC、Build.SERIAL（applyBuildProp 无条件——唯一默认就在伪造的项）
- 基站位置（getCellLocation/getAllCellInfo，fake location 联动）

### 注意问题
1. **生效时机 = guest 下次进程启动**（bindApplication 时 applyBuildProp + 重定向建立）。
   正在运行的 guest 先「结束运行」再切，UI Toast 已提示；
2. 身份**首次开启时随机生成并持久化**（persist 后不再变——变了风控反而异常）；
3. **不是所有读法都能拦**：App 自算的指纹（屏幕分辨率/CPU 型号/传感器特征/安装列表）
   不在设备身份池内，深度指纹对抗需要 guest 侧配合（未来可扩展 VDeviceConfig.buildProp）；
4. 多分身各自独立身份——这是防关联的正确姿势（同一 app 双开两份不共享假 IMEI）。

### 验证
9/9：AIDL 新方法编入、UI 入口/门面在、strings 在、身份池与既有钩子（android_id）回归、
卸载修复回归、引擎四修复回归。真机验证路径见下。

需要同步的文件：`IDeviceManager.aidl`（va2 仓库）/ `VDeviceManagerService.java` /
`VDeviceManager.java` / `VBox.java` / `MainActivity.java` / `strings.xml` + manifest（78 / 2.1.51）。


## 2.1.52：伪装信息手动编辑（管理页 + 逐字段输入）

### 用户需求
2.1.51 的伪装值是首开随机生成的——用户要求**可手动修改**（比如指定一台"目标设备"
的 IMEI/AndroidId，让容器分身完全模拟它）。

### 实现
- **管理页**（长按 → 设备伪装）：标题行 = 总开关（点按切换，回显状态），
  下面 6 个字段项各显示当前值：IMEI / AndroidId / WiFi MAC / 蓝牙 MAC /
  SIM 序列号(ICCId) / Serial；
- **点字段 → 编辑框**：预填当前值，格式提示（15/16/17/20/11 位），保存走
  `VDeviceManager.updateDeviceConfig`（引擎侧全量持久化，DeviceInfoPersistenceLayer）；
  **留空 = 清除该字段**（钩子判 null 自动透传真机值）；
- **AIDL 零新增**：getDeviceConfig/updateDeviceConfig 本来就在；只加了
  `VBox.getDeviceConfig()` 门面。

### 注意问题
1. **生效时机仍是 guest 下次启动**（config 在 bindApplication 应用）；
2. 编辑器用反射写 `VDeviceConfig` 公有字段（deviceId/androidId/wifiMac/
   bluetoothMac/iccId/serial——全是 public String，无 setter，反射是唯一路径）；
3. 总开关切换后**不会**重新随机——身份值一直保留（关闭只是不使用）；
   想换一套新随机值：先关再开（2.1.51 的 setEnable 语义）；
4. 格式提示只是提示——引擎不校验（错误的 IMEI 长度/校验位会原样返回给 guest，
   风控侧可能识别为无效值，用户自己负责）。

### 验证
9/9：编辑器三方法（showFakeDeviceEditor/editFakeField/applyFakeField）编入、
strings 全套在、门面在、卸载修复+引擎四修复回归无损。

需要同步的文件：`MainActivity.java` / `VBox.java` / `strings.xml` + manifest（79 / 2.1.52）。


## 2.1.53：空态双按钮（克隆入口回归）

### 用户反馈
2.1.49 空容器时隐藏底部操作栏后，**克隆功能失去了唯一入口**——空态只有
「安装 APK」一个按钮，想用主空间应用克隆开局的用户被堵死。

### 修法
空态按钮区改双按钮并排（等宽 weight 平分）：
- 左「安装 APK」（蓝胶囊，直接拉 SAF 选包——2.1.49 语义不变）；
- 右「克隆应用」（紫胶囊，新增 `btn_clone_empty` → InstallActivity 主空间列表）。

### 验证
8/8：新 ID/监听编入、直选 APK/底栏互斥/伪装编辑器/卸载修复回归无损。

需要同步的文件：`activity_main.xml` / `MainActivity.java` + manifest（80 / 2.1.53）。


## 2.1.54：策略桩 E 级噪音清零（真机日志 30/31 复盘）

### 日志复盘（番茄小说 · com.dragon.read · Android 16）
两份日志（主进程 + :x 引擎）确认 2.1.49–2.1.53 全链路健康：
- 空态双按钮 → 克隆安装（770 activities 解析）→ launcher 判定
  `SplashActivity`（manifest 扫描命中）✓
- 设备伪装开关 + 手动改 MAC/AndroidId/IMEI + 三次启动成功 ✓
- 划掉任务 FGS 存活（o-stop 检查）✓

### 唯一噪音源：xdja 策略桩
`VAppPermissionManagerService: result is null return false` 一次启动刷 30+ 条 E 级。
链路：telephony/bluetooth 各钩子 → `getAppPermissionEnable(pkg, 权限名)` →
服务端查 `functionMaps`（MDM 策略表）→ **TwinBox 无后台，表永远空 → 查不到 →
返回 false（= 不限制，放行）**。行为完全正确，只是把"无策略"当"错误"打 E 级。
同族 `controllerService: mCSCallback is null`（4 处）——无 MDM 后台注册回调，
app 启停通知无处投递，同理是常态非错误。

### 修法
- `getAppPermissionEnable`：miss 分支 E → D，日志带包名+权限名
  （`no policy (allow by default): <pkg> / <perm>`）；命中分支同样 E → D；
- `controllerService`：4 处 `mCSCallback is null` E → D
  （`no mdm backend, expected`）。

### 注意
返回值一行没动——false 语义（放行）保持。若未来接 MDM 策略后台，
这两处 D 级日志反而是策略未下发的排查锚点。

### 验证
8/8：新日志串编入、旧 E 级串在 dex 中已消、卸载/伪装/空态/引擎四修复回归无损。

需要同步的文件：`VAppPermissionManagerService.java` / `controllerService.java`
+ manifest（81 / 2.1.54）。


## 2.1.55：guest 拿不到签名 → JNI_OnLoad 签名自检 SIGABRT（com.example.ourom）

### 现象（22:44 真机 · 多系统工具箱 com.example.ourom v2.88 · Android 16）
启动即原生崩溃，tombstone：
```
JNI_OnLoad → GetObjectClass(_jobject*)  ← java_object == null → abort
  #06/#07 pc .../libourom.so（so 内部）
  #10 new_nativeLoad（我们 native 引擎的 System.loadLibrary 路径，正常透传）
```

### 根因（与 2.1.45 丢 intent-filter 同根：公开 API 桥的先天缺陷）
安装解析 `parsePackageModern` 走 `getPackageArchiveInfo`——**archive 模式不收集
证书**，`pi.signatures` 恒 null。链条：
1. 安装时 `cache.mSignatures = null` → `savePackageCache` 判 null **不写签名文件**；
2. guest 查询 `GET_SIGNATURES` → `generatePackageInfo` 懒加载 `readSignature`
   → 签名文件不存在 → 仍 null；
3. fallback 查宿主 PM（`getUnHookPackageManager`）→ **宿主没装 ourom**
   → NameNotFoundException → `pi.signatures` 保持 **null**；
4. guest 的 libourom.so JNI_OnLoad 签名自检：`sig[0] == null`
   → `GetObjectClass(null)` → JNI abort。

### 修法（[ApkSignatureReader.java（新增）](third/com/virtual/helper/utils/ApkSignatureReader.java)）
**APK Signing Block 直读**：EOCD → cdStart → 尾 24B magic "APK Sig Block 42"
→ pairs 区全量 **X.509 DER 扫描**（30 82 xx xx + 长度自洽 + ≥400B +
TbsCertificate 双 SEQUENCE 特征 + 去重）。

**为什么是 DER 扫描不是结构化解析**：第一版按 apksig 规范层层剥 LP
（uint32 对长 → V3 优先 → V2 signer），在真实 APK 上**验证失败**——对长其实是
uint64，层级也有漂移（实证：TwinBox 自签 APK 的 V2 块 dump）。改用形态扫描后
与 `apksigner --print-certs` 的证书 sha256 **完全一致**（1cedd7e7…）。
证书字节形态不随 scheme 结构变化，鲁棒。

**接线两处**（[PackageParserEx.java](third/com/virtual/server/pm/parser/PackageParserEx.java)）：
| # | 位置 | 说明 |
|---|---|---|
| 1 | `parsePackageModern` 安装时 | `pi.signatures` 空 → 从 APK 文件直读（新装应用签名文件正常落盘） |
| 2 | `generatePackageInfo` 查询兜底 | 宿主未装（NameNotFoundException）→ 从容器 APK 现场读（**2.1.55 之前装入的存量应用免重装即可获得签名**） |

### 边界
- **V1-only 老包拿不到**（证书在 META-INF/*.RSA 的 PKCS#7 里，不进 Signing Block）；
  Android 16 上这类 target 老的包本就装不进容器，实际影响为零；
- 扫描下限 400B：排除 digest（32B）/公钥（~300B）误报；实测 V2+V3 双块
  同证书去重后恰好一张。

### 验证
9/9：读取器/魔数/两处 fallback 锚点编入，launcher/卸载/引擎四修复回归无损。
真机复现路径：升级后直接打开 ourom（**存量应用不用重装**，查询兜底生效）
——应过 JNI_OnLoad 不再 SIGABRT；`V|PM` 无 `signature read fail` 日志。

需要同步的文件：`ApkSignatureReader.java`（新增）/ `PackageParserEx.java` +
manifest（82 / 2.1.55）。


## 2.1.56：卸载残留——guest 的「sdcard 视图」无人清理（真机截图实锤）

### 现象（真机 5 张截图）
卸载后 `/data/data/dev.twinbox.app/virtual/` 下仍有应用残留：
- `data/user/0/0/`（= `data/data/0/`，软链同目录）里是 guest 视角的
  **sdcard 根**：`.android`、`.FileManagerRecycler`、`.UTSystemConfig`、
  `123云盘`、`.com.excean.gspace`、`.MediaTrash`……全是应用在外置存储写的目录；
- `data/user/1/`（分身）下同样有 `video.player.videoplayer` 与 sdcard 内容。

### 根因
guest 的共享存储经 IO 重定向落到容器内部，**落点有两套**：
1. `user/<userId>/<realUserId>/`——重定向历史落点（截图实锤）；
2. `storage/emulated/<userId>/`——vs 语义路径（`getExternalStorageAppDataDir`
   删的 `Android/data/<pkg>` 只覆盖这套）。

卸载流程（2.1.50 修过包数据目录）对 sdcard 视图里 app 专属内容
（`Android/data/<pkg>`、裸包名目录、dot 变体 `.com.excean.gspace`）
**从来没有清理逻辑**。

### 修法（VAppManagerService.deletePackageDataAsUser）
两个落点根 × 五条子路径全删：
`Android/data|obb|media/<pkg>` + `<pkg>`（裸名） + `.<pkg>`（dot 变体）。
多分身语义保持（按 userId 各自清理）。

### 边界（如实记录）
- **sdcard 根下 app 自定义名的目录不删**（`123云盘` 这类：归属无法枚举，
  删错会伤共享内容）——这些只有 guest 自己知道，容器明确放弃；
- **本版之前的旧残留**：已卸载的应用（PackageSetting 已删）不会再触发
  卸载流程，旧残留需手动清或重装-再卸载一次（新规则生效）；
- 卸载路径调用 ensureCreated 系方法可能留下空目录壳（无数据）。

### 验证
7/8（"sdRoots" 为局部变量名不进 dex，假阴性；特征串 Android/obb、
Android/media PASS 即清理逻辑在包内）+ 签名修复/2.1.50 卸载/引擎四修复回归无损。

需要同步的文件：`VAppManagerService.java` + manifest（83 / 2.1.56）。


## 2.1.57：数据不出容器三防线（MediaScanner 钩子 + .nomedia + 重开 IO 重定向）

### 背景（用户需求：容器内数据不被系统读取图片/视频/文档）
审计结论：Context API 路径早隔离（落在容器私有目录，MediaScanner 无权扫）；
真实泄漏口两个——①native IO 重定向自 2.1.29 起因 Android16 rmdir SIGILL 被关，
guest 裸路径直写真 sdcard 的内容会进真相册；②MediaScannerConnection.scanFile /
MediaStore 无任何钩子，guest 可主动请求系统索引真路径。
（img 镜像方案已评估并否决：无 root 挂载不了、明文 img 挡不住 root、
mmap 兼容死穴——等效能力由本版三防线 + SealedStorage 提供。）

### 修法（三防线 + native 根治）
| # | 防线 | 文件 | 说明 |
|---|---|---|---|
| 1 | **IO 重定向重开（根治）** | `SandboxFs.cpp`（native） | SIGILL 真凶定位：`match_path` 对空条目读 `path[size-1]` 即 `path[-1]` 越界，arm64 对齐检查直接 SIGILL——**不是 libc hook 本身的锅**。修复：空条目防御 + `strdup` 返回堆指针的悬垂问题（拷回 buffer）。`IO_REDIRECT_ENABLE=false→true`，NDK 29 双 ABI 重编译（arm64 550048B / v7a 367064B，字节级验证入包） |
| 2 | **MediaScanner 钩子** | `MediaScannerStub`（新增）+ mirror | binder 层接管 "media_scanner" 服务（与 2.1.43 locale 同构）：scanFile/requestScanFile 的路径在容器树内→放行；指向真 sdcard→吞掉 + W 级留痕（`V|MScan`） |
| 3 | **.nomedia 铺设** | `VActivityManagerService.systemReady()` | 引擎启动幂等铺设：sdcard 视图两落点（user/<id>/<real>/ 与 storage/emulated/<id>/）的数字目录层各放一个；防未来任何扫描机制碰到容器目录 |

### 注意问题
1. **rmdir SIGILL 修复需真机验证**：guest 内文件管理器删除文件/目录、app 删除缓存——
   若再现 SIGILL，把 tombstone 发我（新修的两处都在 IO 路径上，有明确日志锚点）；
2. IO 重定向重开后 **2.1.29 的虚拟存储空目录问题** 已有「目录非空才挂载」守卫，
   理论无回归，但媒体播放（XPlayer/纯音）务必回归；
3. MediaScanner 钩子的 insideBox 判定按宿主数据目录前缀——`scanFile(墙外路径)`
   被吞后 app 自己的相册缩略图可能不刷新（预期行为：墙外路径本就不该由它扫）；
4. .nomedia 只盖数字目录层（userId/real 层），不盖包目录——避免影响 guest
   内部显式扫描自己的媒体。

### 验证
9/9：三防线锚点全在（MediaScannerStub / outside box / V|Nomedia / touchNomedia）、
disabled 串消失（重开生效）、**新 .so 字节级比对一致**（含修复的双 ABI）、
卸载/签名/引擎四修复回归无损。

### 真机验证路径（重要）
1. guest 装回 XPlayer/纯音 → 播放正常（IO 重定向重开的核心回归）；
2. guest 文件管理器删文件/目录 → 无 SIGILL（native 修复验证）；
3. guest 里存图片 → 真机相册**看不到**（主线目标）；
4. `V|MScan` 日志：guest 请求扫描墙外路径时出现 swallowed 留痕；
5. `user/0/0/.nomedia` 文件存在。

需要同步的文件：`SandboxFs.cpp`（va2）/ `VClient.java` / `MediaScannerStub.java` +
`MethodProxies.java` + `IMediaScannerService.java`（新增三件）/ `InvocationStubManager.java` /
`VActivityManagerService.java` + manifest（84 / 2.1.57）。**注意：.so 是重编译的，
GitHub 上的 va2 仓库需要同步 SandboxFs.cpp 改动。**


## 2.1.58：容器文件管理器 + 恶意程序防护（seccomp 内核级加固）

### 背景（用户两问）
①「给容器添加一个文件管理器」→ 容器文件：宿主进程直览/直管容器目录树。
②「用容器调试恶意程序，怎么确保不穿透容器影响物理机」→ 先把真相说清
（VirtualApp 类容器对守规矩 app 是仿真环境，对故意越狱的代码只有内核边界
是真的；libc hook/binder 代理皆可被 raw syscall 绕过，不能当安全边界），
然后把能内核级变硬的全部变硬。

### 新增
| # | 能力 | 实现位置 | 说明 |
|---|---|---|---|
| 1 | **容器文件管理器** | `FileExplorerActivity`（新增）+ `activity_files.xml`/`item_file.xml` | 浏览分身「SD 卡」与应用数据两套根；新建/重命名/删除/复制/剪切/粘贴/排序/详情；MD3 星云蓝令牌；**不提供对外部查看器打开**（文件不出容器） |
| 2 | **恶意程序防护** | `MalwareGuard.cpp`（native，双 ABI）+ `VDeviceConfig` v4 + `VClient` bindApplication 早期 | 分身级开关：**加固模式**=seccomp BPF（内核级拒绝 mount/umount2/init_module/finit_module/delete_module/bpf/perf_event_open/ptrace/process_vm_*/keyctl/add_key/request_key/kexec*/reboot/swapon/off/open_by_handle_at/name_to_handle_at/fanotify_init/userfaultfd/pidfd_getfd/io_uring_setup/fsopen 族/socketcall）；**断网加固**=socket(AF_INET/INET6/PACKET)→EPERM。fork/exec 子进程全继承，raw syscall 不可绕 |
| 3 | **UI 入口** | `MainActivity` ops 第 6 项 + 编辑器；`menu_main.xml` 加「容器文件」 | 多分身先选分身（与设备伪装同构）；断网是加固子集须先开总开关 |
| 4 | **安全文档** | `docs/安全模型与恶意程序分析指南.md` | 两类防线本质区别（内核级 vs 用户态）、已有内核墙（uid/SELinux/scoped storage）、残余风险五条、分析操作建议 |

### VDeviceConfig v4（持久化格式升级）
`malwareGuard`/`netIsolation` 两字段入 parcel（尾部追加）；文件版本 3→4，
**旧文件兼容读**（`VDeviceConfig(Parcel, fileVersion)`：version<4 不读尾部，
默认关闭）——升级不丢设备伪装身份池。

### 关键坑（记录）
- **Android.mk 三段源列表**：LOCAL_SRC_FILES 定义四次（通用段 + arm64 覆盖 +
  arm32 覆盖）——加源文件三段都要加，只加通用段会被覆盖静默丢弃。
- aapt2 二进制 manifest 字符串池：ASCII 名也走 UTF-16 存储——字节级验证
  manifest 要查 `utf-16-le` 编码，查 utf-8 会假阴性。
- seccomp BPF 跳转偏移手数：socket 参数段 7 条指令，非 socket 调用
  jf=7 跳整段落回默认放行（写 4 会误伤非 socket 调用）。

### 验证
15/15：FileExplorerActivity/seccomp 装载点/字段/UI 在 dex、**双 ABI 守卫
符号+MWG 日志串在 .so 且字节级一致**、manifest UTF-16 池注册、字符串资源、
2.1.57 三防线回归无损。

### 真机验证路径
1. 分身长按 → 恶意程序防护 → 开加固+断网 → 重启分身 → 装个要联网的
   app 确认断网（网页转圈/超时）；logcat 找 `V|MWG: malware guard installed`；
2. 加固模式跑正常分身（XPlayer 等）确认无误伤（重点：播放、下载）；
3. 菜单 → 容器文件：浏览/新建/复制/删除，确认路径只落在容器目录树；
4. 旧版本升级安装：设备伪装身份不丢（v4 兼容读）。

需要同步：`MalwareGuard.cpp`（va2，**Android.mk 三段都加**）、`VDeviceConfig.java`/
`DeviceInfoPersistenceLayer.java`/`NativeEngine.java`/`VClient.java`、`MainActivity.java`/
`FileExplorerActivity.java`/两布局/strings/menu/manifest（85/2.1.58）。


## 2.1.59：克隆提速 + 克隆期间列表卡死根治（任务分池）

### 背景（用户实测反馈）
①「克隆应用的速度很慢」；②「克隆时返回主页面再进克隆页，应用列表读不出来」
（截图：正在读取主空间应用… 永久转圈）。

### 病根（三个，一起治）
| # | 病根 | 位置 | 后果 |
|---|---|---|---|
| 1 | **任务全在 AsyncTask 默认串行池** | `InstallActivity` 5 个任务全 `.execute()` | 克隆（几十秒）占住唯一串行线程 → 列表任务在队列里排不上 → 转圈到克隆结束（②的直接原因） |
| 2 | **1KB 缓冲手写拷贝循环** | `FileUtils.copyFile` | 100MB APK = 10 万次 read/write 系统调用，加密存储上雪上加霜（①主犯） |
| 3 | **64 位目录二拷走内存转发** | `V64BitHelper.copyPackage64` | 整份 APK 读进 byte[] + 写进 ashmem 再 binder 转发给 64 位引擎进程落盘——大包克隆双倍写盘 + 400MB 内存峰值（①次犯 + 大包 OOM 风险） |

### 修法
1. **任务分池**：长任务（Clone/MultiOpen/installFromUri）走独立单线程池
   `INSTALL_POOL`（安装彼此仍串行防竞态）；ListHostTask 走 `THREAD_POOL_EXECUTOR`
   ——克隆期间列表照常秒开。收尾加 `isFinishing()` 守卫。
2. **transferTo**：copyFile 改 `FileChannel.transferTo`（内核 sendfile 零拷贝），
   短读保护 + 64KB 缓冲回退；writeToFile 1KB→256KB。
3. **64 位目录硬链**：新 `copyPackage64Fast`——`Os.link`（同 uid 自有文件，
   零 I/O 瞬时）+ 新 provider 方法 `copyLibs64`（只做 lib 提取 + odex，
   跳过 APK 转发）；跨 fs/SELinux 拒链自动回退老全量路径。
   更新/卸载语义不变（unlink 各自独立，inode 由链接计数保护）。
4. **顺手排雷**：`getInstalledApps`/`getInstalledAppsAsUser` 裸遍历 PACKAGE_CACHE →
   快照化（装包 put/remove 与列表遍历并发会 CME → binder 异常 → 客户端列表直接失败）；
   `PackageCacheManager.size()` 锁对象对齐（原锁实例，put/remove 锁 class——两把锁互不排斥，上游手滑）。

### 效果预期
- 克隆耗时：APK 拷贝 ~3-5x（transferTo）+ 64 拷贝归零（硬链）→ 大体感提速一倍以上，
  .so 提取成为剩余主要成本（真实工作量，不可避免）；
- 克隆期间：进出克隆页列表秒开，不再转圈。

### 验证
12/12：分池符号/transferTo/copyPackage64Fast/copyLibs64 全在 dex、2.1.57/2.1.58
回归无损、.so 无变化（纯 Java 版本，86/2.1.59）、签名过。

### 真机验证路径
1. 克隆一个大 App（如带大量 .so 的）：体感速度对比；进行中返回再进 → 列表应秒出；
2. 克隆出的分身正常启动（硬链的 64 位 apk 读取正常）；
3. 卸载宿主侧原 App 后分身仍可运行（拷贝语义未变）；
4. 克隆期间主页面/克隆页来回切换无 ANR。

需要同步：`FileUtils.java` / `V64BitHelper.java` / `VAppManagerService.java` /
`PackageCacheManager.java` / `InstallActivity.java` + manifest（86/2.1.59）。


## 2.1.60：安装任务中心（进度不丢）+ 大包克隆 OOM 根治

### 背景（用户两轮反馈）
①「克隆应用时返回主页再进克隆页面，克隆进度消失了；把安装 APK 按钮换
成一个进度显示的按钮，点一下出现小列表显示克隆进度和 APK 安装进度」；
②三份真机日志：hardlink EACCES + 256MB byte[] OOM（抖音克隆失败）。

### 病根与修法
| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| 1 | 退出页面进度消失 | 任务在静态池（2.1.59）但**状态挂在 Activity 实例** | 新 `InstallCenter` 应用级单例：任务列表/状态/进度全部上移，任何页面任何时刻可见 |
| 2 | 64 位目录硬链真机 EACCES | SELinux 不给 untrusted_app 域 `linkat` 权限（设计时假设自有文件可链——对了一半：POSIX 允许，SELinux 不允许） | fallback 链改 **transferTo 直拷**（不再走老 copyPackage64 内存转发） |
| 3 | 抖音克隆 OOM（Failed to allocate 268435468 byte） | 老 copyPackage64 = 整份 APK 读进 byte[] + ashmem binder 转发，256MB 包必 OOM | 同 #2：直拷零内存峰值；老路径仅作最后兜底 |

### 新交互（按用户规格）
- 「装 APK」按钮 → **任务按钮**：空闲「任务」；有任务时显示最活跃任务百分比
  +计数（如「45% · 2 任务」）；
- 点开**任务面板**（小列表）：顶部「＋ 选择 APK 安装」（原按钮功能收编），
  每任务一行——类型标签（克/多/装）+ 应用名 + 状态行 + **水平精度进度条**；
- **进度是真值不是动画**：克隆/引擎段轮询目标 APK 文件实际增长
  （`TB-poller` 线程，400ms 周期，字节→百分比，上限 88% 后进「安装中…」）；
  APK 安装的 SAF 拷贝段直接按已写字节上报；
- 防重复：同包名任务进行中拒绝再提交；历史保留 8 条，可清已完成；
- `onTaskUpdate` 主线程回调（Activity onStart 注册/onStop 注销，无泄漏），
  任务完成自动刷容器标记。

### 验证
11/11：InstallCenter/面板/轮询/三接口/OOM 修复全在 dex；2.1.57-59 回归无损；
87/2.1.60；纯 Java（.so 不变）。

### 真机验证路径
1. 克隆抖音级别大 App：**不再 OOM**；进行中返回主页再进——按钮显示百分比，
   点开面板进度条在走；
2. 克隆中再克隆另一个：排队（面板两个任务，第一个完成后自动开始第二个）；
3. 64 位目录检查（大 App 装完正常启动）；
4. 老路径兜底不回归：普通 App 克隆照常。

需要同步：`InstallCenter.java`（新）+ `InstallActivity.java` +
`V64BitHelper.java`（fallback 链）+ strings/布局 + manifest（87/2.1.60）。


## 2.1.61：makeApplication 吞异常修复（抖音 40.6 启动 NPE 案·证据链完善）

### 现象（真机日志三件套）
抖音 40.6 克隆成功（2.1.60 OOM 修复生效）但分身启动即崩：
`NPE at VClient.bindApplicationNoCheck:641`——即 `mInitialApplication.getClass()`，
makeApplication 返回 null。

### 破案链
1. TLog 显示 636 行调用前的最后一步正常：dex 打开（2.5s）、抖音
   libsm_impl.so（Sophix 热补）加载、attach 阶段 pref 写入——**抖音
   Application 的初始化确实跑起来了**；
2. 无 "makeApplication FAILED" 记录 → 调用没抛异常 → **返回了 null**；
3. AOSP makeApplication 不存在「正常返回 null」路径 → 唯一解释：内部
   抛了异常，被 `RefMethod.call` 的 catch(InvocationTargetException)
   **吞掉**（只 printStackTrace 到 System.err——本次 logcat 导出恰好
   没抓到 System.err），然后 return null → 641 行 NPE；
4. XPlayer 正常 = 框架路径本身没坏，是抖音 init 抛了个我们从未见过的真异常。

### 修法（可观测性优先，不猜不赌）
1. `VClient`：`makeApplication.call` → **`callWithException`**（解包并
   rethrow 真因）→ 现有的 catch 会把**完整真栈写进 TwinBox 日志文件**；
2. `VClient`：null 守卫——万一真有无异常的 null 路径，明确报错而非裸 NPE；
3. `mirror.RefMethod.call`（全框架收益）：吞异常分支同步 `TLog.e("V|Mirror")`
   落文件。行为零变化（仍返回 null），但从此**任何被 mirror 吞掉的异常
   都有尸检档案**。

### 下一步
真机重跑抖音分身 → 崩溃 → 拉 TwinBox 日志：里面会有
`V|VC makeApplication FAILED for com.ss.android.ugc.aweme` + 真异常
完整栈（或 `V|Mirror swallowed exception from LoadedApk.makeApplication`）。
拿到真栈才能做针对性修复（不猜）。

### 验证
8/8 全过（88/2.1.61，纯 Java）。


## 2.1.62：广播注册包名校验适配（抖音 40.6 启动崩溃·真因修复）

### 案件闭环（2.1.61 埋的证据链生效）
2.1.61 的 callWithException 改造让真栈落进了 TwinBox 日志：
```
AwemeHostApplication.attachBaseContext → AppContextManager（字节通用框架）
  → ContextWrapper.registerReceiver → ContextImpl.registerReceiverInternal
  → IActivityManager$Stub$Proxy.registerReceiverWithFeature
  → MethodInvocationStub.invokeOrigin(368)   ← hook 链放行原始调用
  → SecurityException: Given caller package com.ss.android.ugc.aweme
    is not running in process ProcessRecord{...:dev.twinbox.app:p0}
```

### 根因
Android 16 BroadcastController.registerReceiverWithFeatureTraced 新增
**caller package ↔ ProcessRecord 匹配校验**：广播注册打到真 AMS 时，
callerPackage（guest 包名）与系统进程表里的宿主进程不匹配 → 拒绝。
抖音 attach 阶段（AppContextManager）就注册系统广播 → makeApplication 炸。
旧版本 Android 无此校验——这是又一记针对双开/容器全家桶的系统级收紧。

### 修法
沿用 2.1.5x 的 CALLER_PKG_ARG 集中改写表（TA 设计，护栏完备：仅当参数值
等于当前 guest 包名时才替换为宿主包名，加错位置亦无害）：
- `registerReceiverWithFeature` → 1
- `registerReceiverWithFeatureForCompat` → 1
- `registerReceiver` → 1
（三个变体的 callerPackage 均在 caller 之后第一个 String，index 1。）
放行前换宿主包名过校验；广播投递按 receiver 的 binder 对象回投进程，
注册包名不参与分发——语义不变，系统侧身份校验通过。

### 验证
7/7（89/2.1.62，纯 Java）。

### 真机验证路径
1. 抖音分身启动：应过 attach（本次崩溃点）——若再崩，TwinBox 日志会有
   下一段真栈（V|VC makeApplication FAILED 或 V|Mirror），继续按栈修；
2. 广播功能：抖音能收到时间变化/屏幕亮灭等系统广播（不因改写丢事件）；
3. 其他 guest（XPlayer 等）注册广播无回归。

需要同步：`MethodParameterUtils.java`（表加 3 行）+ manifest（89/2.1.62）。


## 2.1.63：VMOS 式抽屉悬浮球（拖出/拖回跟手交互）

### 背景（用户需求 + 两张 VMOS 参考截图）
「拖动悬浮球让页面拉出来，也可以拉回去」——把 2.0.8 的「自由球 +
居中弹面板」重构为 VMOS 同款侧拉抽屉。

### 交互（与参考截图一致）
- **贴边态**：右缘竖条把手（‹ 箭头，34dp×120dp），面板停屏外；
- **横向拖把手**：面板从右缘**跟手**拉出（实时位移，非弹窗）；
- **反向拖**：跟手收回；
- **松手判定**：拉出过半 → 吸附展开；否则吸附回贴边（280ms
  Decelerate 动画，VMOS 手感）；
- **点按把手**：toggle 展开/收回（动画，兼容老习惯）；
- **展开态点面板外**：收回（FLAG_WATCH_OUTSIDE_TOUCH）；
- **纵向拖**：把手换高度位置（clamp 屏内）；启动分身后自动收回。

### 实现
- 单一 window 容器 `[把手][面板]`（floating_drawer.xml）——两个
  window 变一个，拖动就是平移容器 x，天然跟手、无同步问题；
  FLAG_LAYOUT_NO_LIMITS 允许贴边态面板停屏外；
- FloatingService 重写（~300 行）：handleTouch 手势状态机
  （DOWN 记基线 / MOVE 跟手 clamp / UP 点按 vs 拖动判定 + 吸附）；
- 面板内容沿用原结构（panel_items 动态填充分身列表，启动即收回）；
- 老布局文件保留不删（aapt 全量编译，无引用即无害）。

### 验证
10/10（90/2.1.63；ACTION_OUTSIDE 为编译期内联常量不进字符串池，
以 snapTo/handleTouch/createDrawer 等逻辑符号验证）。

### 真机验证路径
1. 开悬浮球：右缘出现竖条把手；
2. 向左拖：面板跟手拉出，松手过半吸附展开；反向推回；
3. 点按把手：toggle；展开态点面板外：收回；
4. 纵向拖动把手换位置；面板里点应用：启动并自动收回。

需要同步：`FloatingService.java`（重写）+ `floating_drawer.xml`（新）+
`bg_handle.xml`（新）+ strings + manifest（90/2.1.63）。

## 改造清单（相对 VirtualApp-2）
- xdja 安全芯片外部 jar → 6 个行为桩（失败码路径，安全退出）
- support-v4/v7 → 注解桩 + ActivityCompat 手术
- com.lody.virtual.R / BuildConfig → dev.twinbox.app 直构等价物
- 8 处 ECJ 泛型推断 → 显式强转
- 86 个 AIDL → aidl 工具直编（RemoteException 声明补丁）
- 新增宿主 UI 六件套 + HiddenApiBypass

## 许可
GPL（继承 VirtualApp-2）——衍生作品须保持开源。
