package dev.twinbox.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.lody.virtual.remote.InstallOptions;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.AppRunningProcessInfo;
import com.lody.virtual.remote.InstalledAppInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TwinBox V2 容器操作封装：安装/启动/卸载/多开/列表。
 */
public class VBox {

    /**
     * TwinBox 2.0.8：这里原来是直接 return VirtualCore.get() != null。
     * VirtualCore.gCore 是非空静态单例，那个判断永远为 true，引擎真挂了也看不出来。
     * 改用引擎自己在 startup() 里翻转的 isStartUp 标志。
     */
    public static boolean isCoreReady() {
        try {
            return VirtualCore.get().isStartup();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 静默安装 APK 到容器（userId 0） */
    public static InstallResult installApk(Context ctx, String apkPath) {
        VirtualCore core = VirtualCore.get();
        InstallOptions options = InstallOptions.makeOptions(false,
                InstallOptions.UpdateStrategy.COMPARE_VERSION);
        TLog.i("VBox", "installApk: " + apkPath);
        try {
            InstallResult r = core.installPackageSync(apkPath, options);
            if (r != null && r.isSuccess && r.packageName != null) {
                saveLauncher(ctx, r.packageName);
            }
            TLog.i("VBox", "installApk result: success=" + (r != null && r.isSuccess)
                    + " pkg=" + (r == null ? "?" : r.packageName)
                    + " error=" + (r == null || r.error == null ? "none" : r.error));
            return r;
        } catch (Throwable t) {
            TLog.e("VBox", "installApk EXCEPTION", t);
            InstallResult fr = new InstallResult();
            fr.error = "exception: " + t;
            return fr;
        }
    }

    /** 多开分身：把容器内已装包再装一份新 userId */
    public static int cloneToNewUser(String packageName) {
        TLog.i("VBox", "cloneToNewUser: " + packageName);
        VirtualCore core = VirtualCore.get();
        InstalledAppInfo info = core.getInstalledAppInfo(packageName, 0);
        if (info == null) {
            TLog.w("VBox", "cloneToNewUser: not installed in box");
            return -1;
        }
        int[] users = info.getInstalledUsers();
        if (users == null || users.length == 0) {
            // 引擎侧异常：宁可给 -1 让上层报「多开失败」，也不要 NPE
            TLog.w("VBox", "cloneToNewUser: empty installed users for " + packageName);
            return -1;
        }
        // TwinBox 2.1.39：先建虚拟用户，再装包。
        // 06_10-22-05 真机日志：老代码 nextUserId = max(users)+1 直接
        // installPackageAsUser(1, pkg)，但 VUserManagerService.mUserIds 里只有 0
        // ——虚拟用户 1 从未创建，exists(1)=false → 服务端静默 return false
        // → Toast「多开失败」，全程零日志（排查全靠猜）。
        // 正确顺序：createUser 让引擎分配 id（内部走 mPm.createNewUser 建目录、
        // 写 users.xml、落盘），拿到真实的 userId 再 installPackageAsUser。
        // 注意不能假设 createUser 返回的 id 等于 max(users)+1——引擎有自己的
        // mNextUserId 游标（建过又删会漂移），用返回值才是唯一真相。
        com.lody.virtual.os.VUserInfo created =
                com.lody.virtual.os.VUserManager.get().createUser("TwinBox", 0);
        if (created == null) {
            // 上限/落盘失败都走这里——引擎已有日志，这里补宿主侧痕迹
            TLog.w("VBox", "cloneToNewUser: createUser failed (limit reached?)");
            return -1;
        }
        int targetUserId = created.id;
        TLog.i("VBox", "cloneToNewUser: user #" + targetUserId + " created, installing...");
        boolean ok = core.installPackageAsUser(targetUserId, packageName);
        if (!ok) {
            TLog.w("VBox", "cloneToNewUser: installPackageAsUser failed for user #"
                    + targetUserId + " (already installed? engine state?)");
        }
        return ok ? targetUserId : -1;
    }

    public static void launch(final Context ctx, final String packageName, final int userId) {
        TLog.i("VBox", "launch: " + packageName + " userId=" + userId);
        // TwinBox 2.1.4：启动链含同步跨进程序调用（provider init + 权限等待最长 8s），
        // 在主线程跑就是 ANR（5s 输入超时）。挪后台线程，toast 回主线程弹。
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int res = launchInner(ctx, packageName, userId);
                    // TwinBox 2.0.8：启动失败不能一声不响，用户点了图标没反应是最难排查的
                    if (res != 0) {
                        TLog.w("VBox", "launch returned " + res + " for " + packageName);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(ctx, R.string.toast_no_launcher,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (final Throwable t) {
                    TLog.e("VBox", "launch EXCEPTION", t);
                }
            }
        }, "tb-launch").start();
    }

    /** @return VActivityManager.startActivity 的结果，0 = START_SUCCESS */
    private static int launchInner(Context ctx, String packageName, int userId) {
        VirtualCore core = VirtualCore.get();
        Intent i = null;
        try {
            i = core.getLaunchIntent(packageName, userId);
        } catch (Throwable t) {
            TLog.e("VBox", "getLaunchIntent EXCEPTION", t);
        }
        if (i != null) {
            TLog.i("VBox", "query launcher: " + i.getComponent());
        } else {
            TLog.w("VBox", "getLaunchIntent null, use saved launcher");
            String cls = getSavedLauncher(ctx, packageName);
            if (cls != null) {
                i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(packageName, cls);
            }
        }
        // TwinBox 2.1.45：容器 VPackage 里的启动入口可能是错的 —— SAF 装进容器的 APK 宿主没有
        // 同包，老的 launcher 判定落到「APK 里第一个 activity」，而第一个 activity 常常是
        // wxapi.WXEntryActivity 这类透明回调页（点开不崩、只有一个透明界面，见 README 2.1.45）。
        // 这里拿容器 APK 自己的 AndroidManifest.xml 纠一次：容器里已装好的旧数据不用
        // 卸载重装，装上新版 TwinBox 就能立刻修好。
        i = fixLauncherFromApk(packageName, userId, i);
        if (i == null || i.getComponent() == null) {
            TLog.e("VBox", "launch fail: no entry for " + packageName);
            return -1;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // TwinBox 修复：主进程无 AMS hook，必须走 VActivityManager（binder 到 :x 服务端，
        // 服务端已注入 hook 并经 stub 机制拉起 guest 进程）。
        // 绝不能用 ctx.startActivity —— 那会把宿主里的同名应用直接打开！
        try {
            int res = com.lody.virtual.client.ipc.VActivityManager.get()
                    .startActivity(i, userId);
            TLog.i("VBox", "VActivityManager.startActivity result=" + res
                    + " (0=START_SUCCESS) component=" + i.getComponent());
            return res;
        } catch (Throwable t) {
            TLog.e("VBox", "VActivityManager.startActivity EXCEPTION", t);
            return -2;
        }
    }

    /**
     * TwinBox 2.1.45：用容器 APK 自己的 AndroidManifest.xml 校正启动入口。
     *
     * 背景：Android 12+ 引擎解析 APK 走的是公开 API 桥（拿不到 intent-filter），容器里那个
     * MAIN/LAUNCHER 假 filter 得人工挂。老逻辑「宿主 PM 反查不到就取第一个 activity」，
     * 而 SAF 安装的 APK 宿主必然没有同包 → 入口 = APK 里第一个 activity = 常见是
     * wxapi.WXEntryActivity 这类透明回调页 → 用户点开 App 不崩、只有一个透明界面。
     * 实测：07_10-12-39-50_170.log（com.deepseek.chat）。
     *
     * 只有「manifest 扫到了别的入口」且「那个 activity 在容器 VPackage 里确实存在」才改；
     * 任何一步拿不到都原样返回，不影响本来正常的应用。
     */
    private static Intent fixLauncherFromApk(String packageName, int userId, Intent i) {
        try {
            String current = (i == null || i.getComponent() == null)
                    ? null : i.getComponent().getClassName();
            InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(packageName, 0);
            if (info == null) {
                return i;
            }
            String real = apkLauncher(packageName, info);
            if (real == null || real.equals(current)) {
                return i;
            }
            // 目标必须在容器 VPackage 的 activity 表里（activity-alias 之类不在此表，启动会失败）
            if (com.lody.virtual.client.ipc.VPackageManager.get()
                    .getActivityInfo(new android.content.ComponentName(packageName, real), 0, userId) == null) {
                TLog.w("VBox", "apk launcher not in container VPackage, keep current: " + real);
                return i;
            }
            Intent fixed;
            if (i == null) {
                fixed = new Intent(Intent.ACTION_MAIN);
            } else {
                fixed = new Intent(i);
            }
            fixed.setClassName(packageName, real);
            TLog.w("VBox", "launcher corrected by APK manifest: " + current + " -> " + real
                    + " (容器 VPackage 里的入口是错的，「点开只有透明界面」就是这么来的)");
            return fixed;
        } catch (Throwable t) {
            TLog.w("VBox", "fixLauncherFromApk fail: " + t);
        }
        return i;
    }

    /** 容器 APK 里的 MAIN+LAUNCHER 组件名；32/64 位两个资源路径都试一遍 */
    private static String apkLauncher(String packageName, InstalledAppInfo info) {
        for (int variant = 0; variant < 3; variant++) {
            try {
                String path = (variant == 0) ? info.getApkPath() : info.getApkPath(variant == 1);
                if (path == null || !new File(path).exists()) {
                    continue;
                }
                String cls = com.lody.virtual.helper.utils.ApkManifestLauncher
                        .findLauncher(path, packageName);
                if (cls != null) {
                    return cls;
                }
            } catch (Throwable ignore) {
                // 某个变体不存在/不可读：继续试下一个
            }
        }
        return null;
    }

    private static String getSavedLauncher(Context ctx, String packageName) {
        return ctx.getSharedPreferences("launchers", Context.MODE_PRIVATE)
                .getString(packageName, null);
    }

    private static void saveLauncher(Context ctx, String packageName) {
        try {
            String cls = null;
            android.content.Intent li = ctx.getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if (li != null && li.getComponent() != null) {
                cls = li.getComponent().getClassName();
            }
            if (cls == null) {
                // TwinBox 2.0.8：这里原来是个空分支，SAF 安装的 APK（宿主没有同名包）
                // 就存不下启动入口。改为向引擎 PM 要容器内第一个 LAUNCHER activity。
                try {
                    android.content.Intent probe = new android.content.Intent(
                            android.content.Intent.ACTION_MAIN);
                    probe.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                    probe.setPackage(packageName);
                    java.util.List<android.content.pm.ResolveInfo> ris =
                            com.lody.virtual.client.ipc.VPackageManager.get()
                                    .queryIntentActivities(probe, null, 0, 0);
                    if (ris != null && !ris.isEmpty()
                            && ris.get(0).activityInfo != null) {
                        cls = ris.get(0).activityInfo.name;
                        TLog.i("VBox", "launcher from container PM: " + pkgToString(packageName, cls));
                    }
                } catch (Throwable t2) {
                    TLog.w("VBox", "container PM probe fail: " + t2);
                }
            }
            if (cls == null) {
                // 最后兜底：引擎的 getLaunchIntent
                try {
                    android.content.Intent li2 = VirtualCore.get()
                            .getLaunchIntent(packageName, 0);
                    if (li2 != null && li2.getComponent() != null) {
                        cls = li2.getComponent().getClassName();
                    }
                } catch (Throwable t3) {
                    TLog.w("VBox", "engine getLaunchIntent fail: " + t3);
                }
            }
            if (cls != null) {
                ctx.getSharedPreferences("launchers", Context.MODE_PRIVATE)
                        .edit().putString(packageName, cls).apply();
                TLog.i("VBox", "saved launcher " + packageName + " -> " + cls);
            } else {
                TLog.w("VBox", "no launcher class found for " + packageName);
            }
        } catch (Throwable t) {
            TLog.w("VBox", "saveLauncher fail: " + t);
        }
    }

    private static String pkgToString(String pkg, String cls) {
        return pkg + "/" + cls;
    }

    public static boolean uninstall(String packageName) {
        return VirtualCore.get().uninstallPackage(packageName);
    }

    public static void killAll() {
        VirtualCore.get().killAllApps();
    }

    /** 容器内已装应用（含每个包的分身数） */
    public static List<VAppEntry> listInstalled() {
        try {
            return listInstalledInner();
        } catch (Throwable t) {
            TLog.e("VBox", "listInstalled fail (engine not ready?)", t);
            return new ArrayList<VAppEntry>();
        }
    }

    /**
     * TwinBox 2.1.38：容器内是否还有 guest 进程活着（走 :x 引擎进程表）。
     * 供宿主 UI 在 onStart 时判断要不要补挂 KeepAliveService——guest 在后台
     * 跑着但保活通知已丢（main 被低内存回收 / o-stop 打断后用户重开）的场景。
     */
    public static boolean isAnyGuestRunning() {
        try {
            VirtualCore core = VirtualCore.get();
            List<InstalledAppInfo> apps = core.getInstalledApps(0);
            if (apps == null) {
                return false;
            }
            for (InstalledAppInfo info : apps) {
                int[] users = info.getInstalledUsers();
                if (users == null) {
                    continue;
                }
                for (int u : users) {
                    List<AppRunningProcessInfo> ps =
                            VActivityManager.get().getRunningAppProcesses(info.packageName, u);
                    if (ps != null && !ps.isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            TLog.w("VBox", "isAnyGuestRunning fail: " + t);
        }
        return false;
    }

    private static List<VAppEntry> listInstalledInner() {
        List<VAppEntry> raw = listInstalledRaw();
        TLog.i("VBox", "listInstalled: " + raw.size() + " apps in box");
        return raw;
    }

    /**
     * TwinBox 2.0.8：修九宫格内容为空。
     *
     * 2.0.7 这里是：
     *     PackageManager pm = core.getPackageManager();
     *     ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);   ← 宿主 PM！
     *
     * 宿主主进程里 VirtualCore 对 PM 方法的 hook 全部是 isAppProcess() 门控
     * （MethodProxies.GetApplicationInfo / GetInstalledPackages / QueryIntentActivities
     *   都是 return isAppProcess()；MethodProxy.isAppProcess() → VirtualCore.isVAppProcess()），
     * 而 VirtualCore.detectProcessType() 判定宿主主进程为 ProcessType.Main，
     * MethodInvocationStub.invoke() 里 useProxy = ... && methodProxy.isEnable() 为 false，
     * 于是直接 method.invoke(mBaseInterface) 打到系统 PMS。
     * 结果：容器专属包（例如 SAF 装的 APK，宿主没有同名包）抛 NameNotFoundException，
     * 被下面 catch(Throwable) 的「单包失败不拖垮列表」静默吞掉 —— 用户装完 APK 回桌面，
     * 看到的还是「容器是空的」。
     *
     * 正确数据源是引擎 PM：VPackageManager.get().getApplicationInfo(pkg, 0, userId)，
     * 它跨进程打到 :x 引擎进程的 VPackageManagerService，返回从容器 APK 解析出来的
     * ApplicationInfo。VirtualCore.createShortcut() 在宿主主进程里也是这么拿
     * label/icon 的（appInfo.loadLabel(context.getPackageManager())），是引擎验证过的姿势。
     */
    private static List<VAppEntry> listInstalledRaw() {
        List<VAppEntry> out = new ArrayList<VAppEntry>();
        VirtualCore core = VirtualCore.get();
        List<InstalledAppInfo> apps = core.getInstalledApps(0);
        if (apps == null) {
            return out;
        }
        PackageManager pm = core.getPackageManager();
        for (InstalledAppInfo info : apps) {
            try {
                String pkg = info.packageName;
                if (pkg == null || !core.isAppInstalled(pkg)) {
                    continue;
                }
                int[] users = info.getInstalledUsers();
                int uid = (users != null && users.length > 0) ? users[0] : 0;
                VAppEntry e = new VAppEntry();
                e.packageName = pkg;
                e.users = (users != null && users.length > 0) ? users : new int[]{uid};
                e.userId = uid;
                resolveMeta(e, uid, pm);
                out.add(e);
            } catch (Throwable t) {
                // 单包失败不拖垮列表，但必须留痕，不能再静默
                TLog.w("VBox", "listInstalledRaw skip one: " + t);
            }
        }
        TLog.i("VBox", "listInstalledRaw: resolved " + out.size() + "/" + apps.size() + " entries");
        return out;
    }

    /**
     * label / icon 三级兜底，保证列表永不空：
     *   1) 引擎 PM —— 容器内包的正确数据源（读容器里的 APK）
     *   2) 宿主 PM —— 克隆主空间应用时可用，顺带拿到宿主图标
     *   3) 包名 + 系统默认图标 —— 极端情况至少让用户点得到
     */
    private static void resolveMeta(VAppEntry e, int userId, PackageManager pm) {
        boolean haveLabel = false;
        boolean haveIcon = false;
        // 1) 引擎 PM
        try {
            ApplicationInfo ai = com.lody.virtual.client.ipc.VPackageManager.get()
                    .getApplicationInfo(e.packageName, 0, userId);
            if (ai != null) {
                try {
                    CharSequence label = ai.loadLabel(pm);
                    if (label != null) {
                        e.label = label.toString();
                        haveLabel = true;
                    }
                    e.icon = ai.loadIcon(pm);
                    haveIcon = e.icon != null;
                } catch (Throwable t) {
                    TLog.w("VBox", "engine label/icon fail " + e.packageName + ": " + t);
                }
            }
        } catch (Throwable t) {
            TLog.w("VBox", "vpm.getApplicationInfo fail " + e.packageName + ": " + t);
        }
        // 2) 宿主 PM 兜底
        if (!haveLabel || !haveIcon) {
            try {
                ApplicationInfo host = pm.getApplicationInfo(e.packageName, 0);
                if (!haveLabel) {
                    CharSequence label = host.loadLabel(pm);
                    e.label = (label == null ? e.packageName : label.toString());
                    haveLabel = true;
                }
                if (!haveIcon) {
                    e.icon = host.loadIcon(pm);
                    haveIcon = e.icon != null;
                }
            } catch (Throwable ignore) {
                // 宿主没有这个包，正常
            }
        }
        // 3) 名字兜底（icon 由 AppAdapter 兜底成 sym_def_app_icon）
        if (!haveLabel || e.label == null) {
            e.label = e.packageName;
        }
    }

    /** 主空间已装应用（供克隆），排除系统核心与自身 */
    public static List<VAppEntry> listHostApps(Context ctx) {
        List<VAppEntry> out = new ArrayList<VAppEntry>();
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        for (PackageInfo pi : pkgs) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || pm.getLaunchIntentForPackage(pi.packageName) == null) {
                continue;
            }
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            if (pi.packageName.equals(ctx.getPackageName())) {
                continue;
            }
            VAppEntry e = new VAppEntry();
            e.packageName = pi.packageName;
            e.label = ai.loadLabel(pm).toString();
            e.icon = ai.loadIcon(pm);
            e.userId = 0;
            out.add(e);
        }
        return out;
    }

    /** 主空间包克隆进容器（免 APK 文件：用 sourceDir 安装） */
    public static InstallResult cloneHostApp(String packageName) {
        TLog.i("VBox", "cloneHostApp: " + packageName);
        try {
            VirtualCore core = VirtualCore.get();
            InstalledAppInfo existing = core.getInstalledAppInfo(packageName, 0);
            if (existing != null) {
                TLog.i("VBox", "cloneHostApp: already installed in box, users="
                        + Arrays.toString(existing.getInstalledUsers()));
                return null; // 已在容器：走多开
            }
            ApplicationInfo ai = core.getContext().getPackageManager()
                    .getApplicationInfo(packageName, 0);
            TLog.i("VBox", "cloneHostApp sourceDir: " + ai.sourceDir);
            InstallResult r = core.installPackageSync(ai.sourceDir, InstallOptions.makeOptions(false,
                    InstallOptions.UpdateStrategy.COMPARE_VERSION));
            if (r != null && r.isSuccess) {
                saveLauncher(core.getContext(), packageName);
            }
            TLog.i("VBox", "cloneHostApp result: success=" + (r != null && r.isSuccess)
                    + " pkg=" + (r == null ? "?" : r.packageName)
                    + " error=" + (r == null || r.error == null ? "none" : r.error));
            return r;
        } catch (Throwable t) {
            TLog.e("VBox", "cloneHostApp EXCEPTION", t);
            InstallResult fr = new InstallResult();
            fr.error = "exception: " + t;
            return fr;
        }
    }

    public static class VAppEntry {
        public String packageName;
        public String label;
        public Drawable icon;
        public int[] users;
        public int userId;

        /** 分身数量，users 为 null 时按 1 份算（主空间克隆列表不会塞 users） */
        public int copyCount() {
            return users == null || users.length == 0 ? 1 : users.length;
        }
    }

    public static boolean isInstalledInBox(String packageName) {
        try {
            return VirtualCore.get().getInstalledAppInfo(packageName, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** InstallActivity 无 ctx 适配 */
    public static List<VAppEntry> listHostApps() {
        return listHostApps(VirtualCore.get().getContext());
    }
}
