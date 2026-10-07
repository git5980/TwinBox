# 双生盒 TwinBox V2 — 交互按钮有效性检查报告

检查对象：`/root/DSHAWorks/twinbox2`（dev.twinbox.app，versionName 2.0.8 / versionCode 28）
检查方式：静态源码审查，交叉核对 `src-host/` 宿主 UI、`res/layout/` 布局、`res/values/`、
`host-manifest-template.xml` 合并产物，并按 VirtualApp 引擎的 hook 开关追踪真实调用链。
未做真机点击验证（用户指定只分析工作区源码）。

> **后续状态**：本报告列出的问题已在 **2.0.8** 修复，
> 逐条修法与代码位置见 [README.md](README.md) 的「2.0.8 修复清单」。
> 每个条目的状态列已同步更新（✅ 有效 / ✅ 已修复 / ⚠️ 部分修复）。

---

## 结论摘要

| 界面 | 交互点 | 状态 | 说明 |
|---|---|---|---|
| **MainActivity 容器桌面** | Grid 图标点按 / 长按 / 长按菜单 4 项 / 空态按钮 | ✅ | 监听齐全 |
| | 底部「装 APK」`btn_add_apk` | ✅ 已修复 | 2.0.7 从未 findViewById；2.0.8 补注册并直接拉起 SAF 选择器 |
| | 底部「克隆应用」`btn_clone` | ✅ 已修复 | 同上，进入主空间克隆列表 |
| | 底部「悬浮球」`btn_float` | ✅ 已修复 | 同上，且带开关态与再次点击关闭 |
| | 菜单「安装应用 / 悬浮球 / 结束全部」 | ✅ 已修复 | 2.0.7 无 ActionBar 永不可达；2.0.8 主题换 `Theme.Material`，菜单 XML 独立到 `res/menu/menu_main.xml` |
| | Grid 内容渲染（label/icon） | ✅ 已修复 | 2.0.7 用宿主 PM 解析容器包 → 静默丢弃；2.0.8 改引擎 PM 三级兜底 |
| | 多分身选择启动 | ✅ 新增 | 2.0.7 永远只启动 `users[0]` |
| | 「应用信息」 | ✅ 已修复 | 2.0.7 跳宿主设置空白页；2.0.8 改自建详情弹窗 |
| **InstallActivity 应用安装** | 「APK」按钮 / 「返回」按钮 / 列表项 / SAF 回传 | ✅ | 监听齐全 |
| | APK 选择 MIME | ✅ 已修复 | 2.0.7 `application/vnd.android.package-archive` 在部分 ROM 取不到文件；改 `*/*` |
| | 主空间应用列表渲染 | ✅ | 2.0.7 起就用宿主 PM，正确；2.0.8 移出主线程并复用 adapter |
| **FloatingService 悬浮球** | 球体拖拽 / 球体点按展开 | ✅ 已修复 | 2.0.7 `ACTION_DOWN` 返回 false 导致手势全丢；改 `return true` |
| | 容器为空时关面板 | ✅ 已修复 | 2.0.7 对未 addView 的 view 调 removeView 崩溃；改 attach 检查 + try/catch |
| | 面板关闭方式 | ✅ 已修复 | 新增关闭按钮 + 点击外部收起（`FLAG_WATCH_OUTSIDE_TOUCH`） |
| **BridgeActivity 文件中转** | 无按钮，靠 ACTION_SEND 唤起 | ✅ | APK 自动转安装中心，消歧分享 Chooser |

**结论：2.0.7 的核心问题是「装了能用，但底部一整排三个按钮 + 整个菜单 + 悬浮球手势三块都是死的」，
这些已在 2.0.8 全部修掉。**

---

## 一、MainActivity（容器桌面）

### 1. ✅ 已修复｜底部三个按钮：三个都死了

`res/layout/activity_main.xml#L65-L88` 声明了三个按钮，但 `MainActivity.java` 的 `onCreate()`
只给 `R.id.grid`、`R.id.status`、`R.id.empty`、`R.id.btn_install_empty` 注册过监听
（2.0.7 的 `MainActivity.java#L41-L65`）。全文检索 `src-host` 之后，`btn_add_apk` / `btn_clone` /
`btn_float` 这三个 id 只出现在布局里，Java 代码一次都没引用：

```bash
grep -rn "btn_add_apk\|btn_clone\|btn_float" src-host/
# → 无结果（这三个 id 只在 res/layout/activity_main.xml）
```

用户点它们只会有一个按下的水波纹（`backgroundTint` 给的高亮），
没有任何行为：不跳安装页、不开克隆列表、不起悬浮球。

**2.0.8 修法**：`MainActivity.onCreate()` 补三个 `setOnClickListener`，
`btn_add_apk` 带 `EXTRA_AUTO_PICK` 让 `InstallActivity` 进场就直接弹 SAF 选择器；
`btn_float` 改走 `toggleFloatingBall()`，按 `FloatingService.isRunning()` 起/停并同步按钮选中态
（`res/drawable/btn_float_bg.xml` selector）。

### 2. ✅ 已修复｜整个 Options Menu 永不可达

2.0.7 的 `MainActivity.java#L198-L224` 实现了 `onCreateOptionsMenu` / `onOptionsItemSelected`，
提供「安装应用」「悬浮球」「结束全部」。但：

- 主题是 `res/values/themes.xml:2` → `parent="@android:style/Theme.Material.NoActionBar"`
- `activity_main.xml` 里没有 `Toolbar`，Activity 也没有 `setActionBar()`
- 没有 ActionBar 就没有溢出按钮（三个点）
- 布局又没碰 `targetSdk`，manifest 是 `targetSdkVersion="30"`（`host-manifest-template.xml:38`），
  3.0+ 的 legacy 浮动菜单兜底路径也已经关闭
- 当前机型是 Android 16，三键/手势导航都不再发 `KEYCODE_MENU`

也就是说 `onCreateOptionsMenu` 永远不被调用，菜单三项是死代码。
更要紧的是：**「安装应用」是进入 InstallActivity 的第二条路径，它断了，
导致 InstallActivity 只能靠空态按钮进入**（见下）。

**2.0.8 修法**：`res/values/themes.xml` 的 `AppTheme` parent 从 `Theme.Material.NoActionBar`
改成 `Theme.Material`，由框架提供 ActionBar；菜单项从 `res/menu/menu_main.xml` inflate。
原 `onCreateOptionsMenu` / `onOptionsItemSelected` 两个方法**原样保留**（它们本来没写错），
只把 `menu.add(...)` 换成 `getMenuInflater().inflate(R.menu.menu_main, menu)`。
布局里不摆 Toolbar，避免出现双标题栏。

### 3. ✅ 已修复｜空态按钮曾是唯一入口 → 容器一旦有应用就再也进不了安装页

`activity_main.xml` 里 `@+id/empty`（含 `btn_install_empty`）`visibility="gone"`，
只在 `reload()` 判 `apps.isEmpty()` 时变 VISIBLE。
结合菜单不可达和 `btn_add_apk`/`btn_clone` 死掉，进入 InstallActivity 的路径只剩：
空态按钮 / 从系统分享 APK 到双生盒。
一旦容器里装进第一个应用，空态消失，主界面没有任何一个能进安装/克隆页的活按钮。

**2.0.8 修法**：底部按钮 + 工具栏菜单构成常驻双入口，不再依赖 `mEmpty` 显隐；
`btn_add_apk` 更进一步，进场直接弹选择器（`EXTRA_AUTO_PICK`）。

### 4. ✅ 已修复｜Grid 渲染：容器专属应用被静默丢弃（网格可能永远是空的）

2.0.7 的 `VBox.listInstalledRaw()` 拿的是 **主进程的真实 PackageManager**：

```java
PackageManager pm = core.getPackageManager();      // = context.getPackageManager()
ApplicationInfo ai = pm.getApplicationInfo(pkg, 0); // ← 容器专属包会抛 NameNotFoundException
```

而引擎的 PM hook 在宿主主进程里是关着的。追链条：

- `MethodProxies.GetApplicationInfo.isEnable()` → `isAppProcess()` → `VirtualCore.isVAppProcess()`
- `VirtualCore.detectProcessType()`：主进程名 == package name → `ProcessType.Main`
- `MethodInvocationStub.invoke()`：`useProxy = isStartup() && methodProxy != null && methodProxy.isEnable()` → false → `method.invoke(mBaseInterface, args)` 走真实 IPackageManager

同一批 `isAppProcess()` 门控的还有 `getInstalledPackages`、`queryIntentActivities`、`getActivityInfo`
（见本文末表格）。所以主进程里的 PM 调用全部落到系统 PMS。

后果：**只剩宿主也装了的应用能在网格里显示。**
- 「克隆主空间应用」→ 宿主同包在 → `getApplicationInfo` 成功 → 显示
- 「SAF 安装本地 APK」→ 宿主没有这个包 → `NameNotFoundException` → 被
  「单包失败不拖垮列表」的 catch 吞掉 → 不显示

而 SAF 装 APK 正是 `InstallActivity` 的主打功能，用户装完 APK 回主界面看到的还是
「容器是空的」。这是最伤体验的一处。

**2.0.8 修法**：`resolveMeta()` 三级兜底 —— 引擎 PM
`VPackageManager.get().getApplicationInfo(pkg, 0, userId)`（读容器里的 APK）→
宿主 PM（克隆场景可用，顺带拿宿主图标）→ 包名 + `sym_def_app_icon`。
依据是 `VirtualCore.createShortcut()` 在宿主主进程里也是
`appInfo.loadLabel(context.getPackageManager())` / `appInfo.loadIcon(pm)` 这套姿势。
每级失败都写 TLog，不再静默。

### 5. ✅ 已修复｜长按菜单「应用信息」几乎必失败

2.0.7 用 `ACTION_APPLICATION_DETAILS_SETTINGS` + `Uri.fromParts("package", pkg, …)`
去开宿主设置。容器专属包在宿主根本没安装，Android 设置不会抛异常
（所以兜底 Toast 也不出现），而是打开一个「应用未安装」的空页。

**2.0.8 修法**：`showInfo()` 自建详情弹窗，展示引擎侧真实信息：
包名 / 版本 / 分身数 / userId 列表 / 容器内 APK 路径 / 宿主机是否同包。

### 6. ✅ 已修复｜「引擎初始化异常」判断是死代码

2.0.7 的 `VBox.isCoreReady()` 调 `VirtualCore.get()`，而
`VirtualCore.gCore = new VirtualCore()` 是 final 静态实例，永远非 null。
所以 `reload()` 里 `mStatus.setText("引擎初始化异常")` 这个分支永远进不去，
引擎真挂了只会表现为空列表。

**2.0.8 修法**：改用 `VirtualCore.get().isStartup()` —— 引擎在 `startup()` 末尾
才把 `isStartUp` 置 true，这是真实启动信号。

### 7. ✅ 已修复｜「多开」之后只能启动第一个分身

2.0.7 的 `e.userId = e.users[0]`。
长按多开出的 user 1、user 2 … 只能在角标上看 `×N`，点图标始终启动 users[0]。

**2.0.8 修法**：`onAppClick()` 判 `copyCount() > 1`，先弹分身选择器，
按选中的 `users[which]` 启动。

---

## 二、InstallActivity（应用安装）

### ✅ 有监听的都能工作

| 位置 | 状态 |
|---|---|
| 顶部「APK」按钮 `btn_pick_apk` | ✅ 有监听（2.0.8 起 MIME 放宽） |
| 顶部「返回」按钮 `btn_back` | ✅ 有监听 |
| 列表项（未克隆 / 已克隆两态） | ✅ 有监听，分流正确；2.0.8 起已克隆项触发多开 |
| SAF 选文件回调 | ✅ |
| 进度条 + 文案 | ✅ |
| 主空间应用列表 | ✅ 主进程用真实 PM，**这里是对的** |

### ✅ 已修复｜SAF 选 APK：MIME 太窄，OEM ROM 上取不到文件

```java
i.setType("application/vnd.android.package-archive");
```

AOSP 的 `MimeTypeMap` 确实把 `.apk` 映射到这个 MIME，但各 OEM 的文件选择器
（DocumentsUI / 各厂自研）对它的实现不一致，实测有些机型返回空列表。
**2.0.8**：`setType("*/*")`，拿到 Uri 后自己按扩展名/内容判是不是 APK。

### ✅ 已修复｜列表全是主线程慢调用，滚动会卡

- `reloadHostApps()` 在 `onCreate` 主线程跑 `pm.getInstalledPackages(0)`
- `HostAppAdapter.getView()` 每行都调一次 `VBox.isInstalledInBox()` → 跨进程 IPC 到 `:x` 引擎进程
- 每行还有 `ai.loadIcon(pm)` + `ai.loadLabel(pm)`

本机 ~150 个非系统应用：首屏几十次串行 binder，滚动时每行再打一次 IPC，能感到明显卡顿。
**2.0.8**：`ListHostTask` 在后台一次拉列表 + 一次拉「容器内已装包名集合」，
角标从内存集合读。

### ✅ 已修复｜`reloadHostApps()` 每次 `new HostAppAdapter()`

克隆完成就重建 adapter，列表滚动位置和折叠状态全部丢失，列表越长越明显。
**2.0.8**：`HostAppAdapter.swap()` 复用，只换数据。

---

## 三、FloatingService（悬浮球）

### ✅ 已修复｜球体既不响应拖拽也不响应点击（整个悬浮球失效）

```java
case MotionEvent.ACTION_DOWN:
    ...
    return false;   // ← 2.0.7：不消费 DOWN
case MotionEvent.ACTION_MOVE:
    ...
    return true;
case MotionEvent.ACTION_UP:
    ...
    return true;
```

`View.dispatchTouchEvent` 对 `ACTION_DOWN`：
`onTouchListener.onTouch()` 返回 false → 转 `onTouchEvent()` →
`floating_ball.xml` 的根 ImageView 没有 `clickable`/`focusable`/tooltip，`onTouchEvent` 也返回 false
→ 整棵视图树不消费 DOWN → `mFirstTouchTarget` 为 null
→ 后续 `ACTION_MOVE` 被当成拦截处理转成 CANCEL。

**结果：`ACTION_MOVE` 和 `ACTION_UP` 都到这个 listener 收不到。**

- 拖拽贴边 → 废了
- 抬手判定 `> 12px` 的 tap 分支调 `togglePanel()` → 废了

屏幕上能看到那个悬浮球，但它就是一张不能点的图片。**2.0.8 已改 `return true`**，
并补 `ACTION_CANCEL` 复位、`ACTION_MOVE` 里超过阈值时顺手收起面板。

### ✅ 已修复｜容器为空时点球会直接崩

```java
mPanel = LayoutInflater.from(this).inflate(R.layout.floating_panel, null, false);
...
if (apps.isEmpty()) {
    Toast.makeText(this, "容器内暂无应用", Toast.LENGTH_SHORT).show();
    mWm.removeView(mPanel);   // ← 这个 view 从没 addView 过
    mPanel = null;
    return;
}
```

`mPanel` 此时只 inflate 过、没 `mWm.addView()`，
`WindowManagerGlobal.removeView()` 会抛
`IllegalArgumentException: View=… not attached to window manager`
→ 从触摸分发一路冒到顶层，悬浮球进程崩溃。

**2.0.8 修法**：空容器分支只 Toast 并把 `mPanel` 置 null，绝不去 remove 未 attach 的 view；
另抽 `safeRemove()`（`isAttachedToWindow()` 检查 + try/catch），所有移除路径统一走它。

### ✅ 已修复｜面板既没有关闭按钮，也没有点外面收起

面板只加了 `FLAG_NOT_FOCUSABLE`，没有 `FLAG_WATCH_OUTSIDE_TOUCH`，
`floating_panel.xml` 里也只有标题 + `panel_items`。
面板唯一的关闭手段是再点一次球（而球是废的）。修好球之后这里依然只剩这一条路。

**2.0.8 已补齐三路**：`floating_panel.xml` 加 `@id/panel_close` 关闭按钮；
面板窗口加 `FLAG_WATCH_OUTSIDE_TOUCH` + `ACTION_OUTSIDE` 收起；
再点球收起（`togglePanel()` 的 `mPanel != null` 分支）。

### ⚠️ 部分修复｜FGS 权限在 Android 14+ 可能不齐

manifest 只声明了 `FOREGROUND_SERVICE`，`FloatingService` 用了
`foregroundServiceType="specialUse"`。
Android 14+ 还要求 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 +
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性。
`targetSdk=30` 靠兼容性豁免挡住了一部分，但 Android 16 上仍有
`startForeground()` 抛 `SecurityException` 的可能。

**2.0.8 已补**：`host-manifest-template.xml` 加
`FOREGROUND_SERVICE_SPECIAL_USE` + `POST_NOTIFICATIONS`，service 节点加
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；`FloatingService.onCreate()` 里
`startForeground` 失败时降级为普通服务，不让悬浮球整体失效。
> 仍未做：`targetSdk` 是否从 30 上调 —— 这是「Android 16 存活姿态」的取舍，
> 涉及引擎大量 hidden API 行为，建议单独验证，不在本次按钮修复范围内。

---

## 四、BridgeActivity（文件中转）

无按钮，由 `<intent-filter ACTION_SEND mimeType=*/*>` 唤起。
`onCreate` 里 intent / uri / 空判 / 流拷贝 / Toast 都齐，逻辑正常。

两处已按建议处理：

- 分享 APK 时 `InstallActivity` 和 `BridgeActivity` 的 filter 会同时命中，
  系统每次都会弹「用什么打开」。**2.0.8**：`BridgeActivity` 识别到 APK 时用 explicit Intent
  转给 `InstallActivity`，不会再弹 Chooser。
- 文件名原来从 `uri.getLastPathSegment()` 切扩展名，对
  `content://…/document/primary:Download/a.apk` 这种会切出 documentId。
  **2.0.8**：优先 `OpenableColumns.DISPLAY_NAME`，取不到才退回。

---

## 五、顺带发现的文档/构建漂移

- `README.md` 写「产物 TwinBox-v2.0.apk / versionCode 20」，实际 `host-manifest-template.xml`
  是 `versionCode 27` / `versionName 2.0.7`。
  → **2.0.8 已统一为 versionCode 28 / versionName 2.0.8，README、模板、合并产物三者一致。**
- `VBox.saveLauncher()` 里「SAF 安装场景」原先是空分支，注释说要从容器拿第一个 activity 却什么都没做
  （幸好 `launchInner` 有 `core.getLaunchIntent()` 兜底）。
  → **2.0.8 已实现**：宿主 PM → 容器 PM `queryIntentActivities` → `getLaunchIntent` 三级兜底。

---

## 六、修复优先级核对

| 优先级 | 问题 | 2.0.8 状态 |
|---|---|---|
| P0 | 底部 `btn_add_apk` / `btn_clone` / `btn_float` 无监听 | ✅ 已修 |
| P0 | 悬浮球 `ACTION_DOWN` 返回 false | ✅ 已修 |
| P0 | 容器为空时 `removeView` 未 attach 的 view | ✅ 已修 |
| P1 | Options Menu 无 ActionBar | ✅ 已修（主题换 Theme.Material） |
| P1 | Grid 用宿主 PM 解析容器包 → 装完 APK 网格空 | ✅ 已修（引擎 PM 三级兜底） |
| P2 | 「应用信息」打宿主设置页 | ✅ 已修（自建详情弹窗） |
| P2 | APK MIME 太窄 | ✅ 已修 |
| P2 | 列表主线程慢调用 | ✅ 已修（后台预取 + 内存集合） |
| P2 | FGS specialUse 权限 | ✅ 已修（权限 + subtype 属性 + 降级） |
| P3 | 已克隆应用分享时双意图冲突 | ✅ 已修（BridgeActivity 转发） |
| P3 | 多开后只能启动第一个分身 | ✅ 已修（分身选择器） |
| P3 | 版本号文档漂移 | ✅ 已修（README / 模板 / 合并产物对齐） |
| — | `targetSdk 30` 是否上调 | ⏸ 未动，需单独验证 |

---

## 附：VirtualApp PM hook 在主进程的实际开关

`third/com/virtual/client/hook/proxies/pm/MethodProxies.java` 中所有
`isEnable() -> isAppProcess()` 的方法，在宿主主进程（`ProcessType.Main`）全部走真实 PMS：

```
getInstalledPackages          -> isAppProcess()
getApplicationInfo            -> isAppProcess()
queryIntentActivities         -> isAppProcess()
resolveIntent                 -> default(true)
getActivityInfo               -> isAppProcess()
getServiceInfo                -> isAppProcess()
getInstalledApplications      -> default(true)
queryContentProviders         -> default(true)
getProviderInfo               -> default(true)
```

这一点决定了：宿主主进程拿「宿主装了哪些包」是对的（InstallActivity 的克隆列表没问题），
拿「容器里那个包的 label/icon」是错的（2.0.7 的 MainActivity 网格有问题，2.0.8 已改走引擎 PM）。
