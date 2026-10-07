# TwinBox 双生盒

**Android 应用容器 / 多开引擎**——在普通 App 权限内构建一套完整的应用运行环境：
免 root、免刷机、免系统安装器，把任意 APK「装进盒子里」跑。

对标 VMOS Pro 的现代开源实现，基于 [pinggle/VirtualApp-2](https://github.com/pinggle/VirtualApp-2)
（GPL-3.0）深度改造，重点适配 **Android 12–16**。

```
┌────────────────────────── 宿主 Android ──────────────────────────┐
│  dev.twinbox.app（宿主 UI：容器桌面 / 安装中心 / 悬浮球）           │
│    ├── 主进程                                                     │
│    └── :x 引擎进程（VA 服务端：AMS / PMS / 存储密封 …）             │
│          └── guest 进程（被容器化的应用，运行在虚拟身份下）          │
└───────────────────────────────────────────────────────────────────┘
```

## 功能特性

- **静默安装**：APK 从 SAF 文件选择 / 分享中转桥直接装进容器，不经过系统安装器
- **应用多开**：主空间已装应用一键克隆；同一应用可建多个分身
- **容器桌面**：九宫格布局 + 分身角标 + 多分身选择启动
- **悬浮球**：可拖拽快捷面板（拖拽 / 点按展开 / 外部点击收起）
- **文件中转桥**：系统分享（ACTION_SEND）接收文件，APK 自动转入安装中心
- **可选 at-rest 加密**（SealedStorage）：容器内数据落盘加密，
  AndroidKeyStore AES-256-GCM + 挂载点全生命周期审计。默认关闭，
  `files/.twinbox_seal_on` 显式启用（详见 CHANGELOG 2.1.46 的事故与重写记录）

## 兼容性

主验证真机：**OnePlus / Android 16（SDK 36）**——高版本系统校验最严的环境。

Android 12–16 适配要点（全部有真机日志验证，逐项分析见 [CHANGELOG.md](CHANGELOG.md)）：

- HiddenApiBypass 元反射豁免 + targetSdk 30 姿态
- 80+ 项 binder 层钩子与身份改写：权限检查三路、AttributionSource、
  PendingIntent/JobScheduler 虚拟 id、LocaleManager、Autofill、
  输入法静态缓存（Android 16 新架构）、音频 AppOps、Toast/定位改名、
  IO 重定向总闸（native 引擎）
- guest 实测：DeepSeek 2.6.1、微信、XPlayer、纯音 等

## 构建

依赖：JDK 8+ · Android SDK（build-tools 34.0.0 + platforms/android-34）·
ECJ · Python 3 · zip/keytool（JDK 自带）

```bash
# 1. 本仓库与 VirtualApp-2 原仓库克隆为同级目录
#    （AIDL / lib manifest / native 引擎 libv++.so 均取自 va2）
git clone https://github.com/<你的用户名>/TwinBox.git twinbox2
git clone https://github.com/pinggle/VirtualApp-2.git va2

# 2. 构建（无 Gradle，直构链 aapt2 + ECJ + d8 + apksigner）
cd twinbox2
bash build.sh
# → TwinBox-v2.0.apk（dev.twinbox.app / versionCode 74 / versionName 2.1.47）
```

可覆盖的环境变量：`ANDROID_SDK`（默认 `/home/z/android-sdk`）、`ECJ_JAR`、
`KEYSTORE` / `STOREPASS`（默认在 `build/` 现场自签）。

说明：
- 根目录 `AndroidManifest.xml` 是 `merge_manifest.py` 的生成物，构建时自动重建；
- native 引擎按 ABI（`libv++.so` / `libv++_64.so`）从 `../va2` 探测打入双 ABI；
  找不到时构建告警但不失败（IO 重定向/沙箱 hook 不可用，见 CHANGELOG 2.1.22）。

## 架构（相对 VirtualApp-2 的改造）

| 改造点 | 说明 |
|---|---|
| 构建 | 剥离 Gradle/AAR：860+ 源文件 ECJ 直构，86 个 AIDL aidl 工具直编 |
| 兼容 | xdja 安全芯片 jar → 6 个行为桩（失败码路径安全退出）；support-v4/v7 → 注解桩 |
| 身份 | `com.lody.virtual.R`/BuildConfig → `dev.twinbox.app` 直构等价物 |
| 宿主 UI | 新增六件套：容器桌面 / 安装中心 / 悬浮球 / 设置 / KeepAliveService / TLog |
| Android 12–16 | HiddenApiBypass + 大量 binder 钩子修复（见 CHANGELOG） |
| 加密 | SealedStorage：spawn 前解封 / 末进程死后密封 / 引擎启动扫尾三挂载点 |

## 文档

- **[CHANGELOG.md](CHANGELOG.md)**——v2.0.8 → v2.1.47 共 40+ 版完整修复记录。
  每个问题按「现象（真机日志）→ 根因（对照 AOSP 源码）→ 修法 → 验证」闭环记录，
  是本项目最有价值的部分：涉及 Android 12–16 虚拟化的绝大多数深坑
  （归属校验、oneway 异常不回传、进程级静态缓存、软链遍历误擦 mmap 文件等）。
- **[BUTTON-CHECK.md](BUTTON-CHECK.md)**——宿主 UI 交互的静态审查报告。

## 已知限制

- **intent-filter 虚拟化缺口**：Android 12+ 引擎走公开 API 桥解析 APK，
  guest 包内隐式 intent（queryIntentActivities / deep link / receiver 按 action 匹配）
  拿不到结果（launcher 入口已单独修复，见 CHANGELOG 2.1.45）。
  manifest 扫描地基（`ApkManifestLauncher`）已就绪，待补全。
- **SealedStorage 默认关闭**：2.1.42–2.1.45 曾因软链遍历误擦 guest 正在执行的
  原生库导致 SIGILL，2.1.46 以三重护栏重写后转为显式启用。

## 许可

[GPL-3.0](LICENSE)（继承 VirtualApp-2，衍生作品须保持开源）。

本项目仅供学习研究。使用容器运行第三方应用时，请遵守相应应用的用户协议与当地法律。
