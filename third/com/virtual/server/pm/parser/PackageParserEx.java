package com.lody.virtual.server.pm.parser;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.core.SettingConfig;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.fixer.ComponentFixer;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.compat.PackageParserCompat;
import com.lody.virtual.helper.utils.ApkManifestLauncher;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.PackageCacheManager;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.PackageUserState;
import com.xdja.zs.InstallerSettingManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import mirror.android.content.pm.ApplicationInfoL;
import mirror.android.content.pm.ApplicationInfoN;

/**
 * @author Lody
 */

public class PackageParserEx {

    private static final String TAG = PackageParserEx.class.getSimpleName();

    private static final ArrayMap<String, String[]> sSharedLibCache = new ArrayMap<>();

    public static VPackage parsePackage(File packageFile) throws Throwable {
        // TwinBox 补丁：Android 12+ 老反射解析失效，优先走公开 API 桥
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            VPackage modern = parsePackageModern(packageFile);
            if (modern != null) {
                return modern;
            }
            // 桥失败（极端包）再落回老路径尝试
        }
        PackageParser parser = PackageParserCompat.createParser(packageFile);
        if (BuildCompat.isQ()) {
            parser.setCallback(new PackageParser.CallbackImpl(VirtualCore.getPM()));
        }
        PackageParser.Package p = PackageParserCompat.parsePackage(parser, packageFile, 0);
        if (p.requestedPermissions.contains("android.permission.FAKE_PACKAGE_SIGNATURE")
                && p.mAppMetaData != null
                && p.mAppMetaData.containsKey("fake-signature")) {
            String sig = p.mAppMetaData.getString("fake-signature");
            buildSignature(p, new Signature[]{new Signature(sig)});
            VLog.d(TAG, "Using fake-signature feature on : " + p.packageName);
        } else {
            try {
                int flag = 0;
                if (BuildCompat.isPie()) {
                    flag |= PackageParser.PARSE_IS_SYSTEM_DIR;
                } else {
                    flag |= PackageParser.PARSE_IS_SYSTEM;
                }
                PackageParserCompat.collectCertificates(parser, p, flag);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return buildPackageCache(p);
    }

    private static void buildSignature(PackageParser.Package p, Signature[] signatures) {
        if (BuildCompat.isQ()) {
            Object signingDetails = mirror.android.content.pm.PackageParser.Package.mSigningDetails.get(p);
            mirror.android.content.pm.PackageParser.SigningDetails.pastSigningCertificates.set(signingDetails, signatures);
            mirror.android.content.pm.PackageParser.SigningDetails.signatures.set(signingDetails, signatures);
        } else {
            p.mSignatures = signatures;
        }
    }

    /**
     * TwinBox 补丁：公开 API 解析桥（Android 12-16）。
     * getPackageArchiveInfo 跨版本稳定；组件 intent-filter 为空（显式 intent 场景不受影响），
     * 所以 MAIN/LAUNCHER 这一个 filter 只能人工补 —— 补在哪个 activity 上就是启动入口。
     *
     * TwinBox 2.1.45：launcher 判定改成三级（原来只有「宿主 PM 反查 → 首个 activity」两级。
     * 原来那条兜底不只在宿主没有同包时触发：宿主有同包但拿到的类名在这份 APK 的 activity
     * 表里对不上（版本不同）、或 getLaunchIntentForPackage 抛异常/返回 null，都会落到
     * 「首个 activity」——受影响的是所有「APK 里第一个 activity 不是主界面」的应用）：
     *   1) {@link ApkManifestLauncher} 直接扫容器 APK 的 AndroidManifest.xml（权威）；
     *   2) 宿主装了同包 → 借宿主 PM 反查（老逻辑，保留）；
     *   3) 名字启发式（MainActivity / LauncherActivity / SplashActivity …）；
     *   4) 都拿不到才退回首个 activity，并打 W 级日志留痕。
     * 触发本改动的实测日志（DeepSeek / com.deepseek.chat，点图标只有透明界面、不崩）：
     *   TwinBox/V|Core: getLaunchIntent q2(LAUNCHER) size=1
     *   TwinBox/VBox:   query launcher: ComponentInfo{com.deepseek.chat/com.deepseek.chat.wxapi.WXEntryActivity}
     *   TransparentWindowDetector: Detect Empty window:com.deepseek.chat/...WXEntryActivity
     */
    private static VPackage parsePackageModern(File packageFile) {
        try {
            android.content.pm.PackageManager pm = VirtualCore.getPM();
            int flags = android.content.pm.PackageManager.GET_ACTIVITIES
                    | android.content.pm.PackageManager.GET_SERVICES
                    | android.content.pm.PackageManager.GET_RECEIVERS
                    | android.content.pm.PackageManager.GET_PROVIDERS
                    | android.content.pm.PackageManager.GET_PERMISSIONS
                    | android.content.pm.PackageManager.GET_META_DATA
                    | android.content.pm.PackageManager.GET_SIGNATURES
                    | android.content.pm.PackageManager.GET_CONFIGURATIONS;
            PackageInfo pi = pm.getPackageArchiveInfo(packageFile.getAbsolutePath(), flags);
            if (pi == null || pi.applicationInfo == null) {
                VLog.w(TAG, "modern parse: archive info null");
                return null;
            }
            ApplicationInfo ai = pi.applicationInfo;
            String apkPath = packageFile.getAbsolutePath();
            ai.sourceDir = apkPath;
            ai.publicSourceDir = apkPath;
            // TwinBox 2.1.45：launcher 三级判定（详见 parsePackageModern 上方注释）
            // 1) 权威来源：直接扫容器 APK 的 AndroidManifest.xml，不依赖宿主装没装同包
            String launcherClass = ApkManifestLauncher.findLauncher(apkPath, pi.packageName);
            if (launcherClass != null && !hasActivityNamed(pi.activities, launcherClass)) {
                VLog.w(TAG, "manifest launcher not in activity list: " + launcherClass
                        + " (pkg=" + pi.packageName + ")");
                launcherClass = null;
            }
            if (launcherClass != null) {
                // 诊断用：manifest 自己把透明页声明成入口的 APK（改过包/第三方魔改包），
                // 这种情况本补丁救不了，日志里必须一眼看得出来，别又当成容器 bug 查一轮。
                ActivityInfo li = findActivity(pi.activities, launcherClass);
                if (li != null && isTranslucentTheme(li.theme)) {
                    VLog.w(TAG, "manifest launcher theme is Translucent: " + launcherClass
                            + " (pkg=" + pi.packageName + ")：APK 的 manifest 本身把透明页声明成"
                            + " 入口，若点开只有透明界面属于该 APK 自身问题，需单独评估");
                }
            }
            // 2) 宿主装了同包 → 借宿主 PM 反查（老逻辑，保留；SAF 安装的 APK 这条会落空）
            if (launcherClass == null) {
                try {
                    android.content.Intent li = pm.getLaunchIntentForPackage(pi.packageName);
                    if (li != null && li.getComponent() != null
                            && hasActivityNamed(pi.activities, li.getComponent().getClassName())) {
                        launcherClass = li.getComponent().getClassName();
                        VLog.d(TAG, "launcher from host PM: " + launcherClass);
                    }
                } catch (Throwable ignore) {
                }
            }
            // 3) 名字启发式（manifest 扫不动时的次优解：MainActivity / SplashActivity …）
            if (launcherClass == null) {
                launcherClass = guessLauncherByName(pi.activities);
                if (launcherClass != null) {
                    VLog.w(TAG, "launcher guessed by name: " + launcherClass
                            + " (pkg=" + pi.packageName + ", manifest scan unavailable)");
                }
            }

            VPackage cache = new VPackage();
            cache.packageName = pi.packageName;
            cache.mVersionName = pi.versionName;
            long vc = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            cache.mVersionCode = (int) vc;
            cache.applicationInfo = ai;
            cache.mAppMetaData = ai.metaData;
            cache.mSignatures = pi.signatures;
            cache.requestedPermissions = new ArrayList<>();
            if (pi.requestedPermissions != null) {
                cache.requestedPermissions.addAll(java.util.Arrays.asList(pi.requestedPermissions));
            }
            cache.usesLibraries = null;
            cache.usesOptionalLibraries = null;
            cache.permissions = new ArrayList<>();
            cache.permissionGroups = new ArrayList<>();
            cache.instrumentation = new ArrayList<>();

            // launcher filter（MAIN/LAUNCHER）——供 VirtualCore.getLaunchIntent 查询
            android.content.IntentFilter launcherFilter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_MAIN);
            launcherFilter.addCategory(android.content.Intent.CATEGORY_LAUNCHER);

            boolean launcherSet = false;
            cache.activities = new ArrayList<>();
            if (pi.activities != null) {
                for (ActivityInfo info : pi.activities) {
                    VPackage.ActivityComponent c = new VPackage.ActivityComponent(info);
                    if (!launcherSet && launcherClass != null && launcherClass.equals(info.name)) {
                        VPackage.ActivityIntentInfo ii = new VPackage.ActivityIntentInfo();
                        ii.filter = launcherFilter;
                        c.intents.add(ii);
                        launcherSet = true;
                    }
                    cache.activities.add(c);
                }
            }
            if (!launcherSet && !cache.activities.isEmpty()) {
                // 最后兜底：仍然给首个 activity 挂 launcher filter（不挂的话桌面/启动链完全没入口），
                // 但必须留 W 级痕迹 —— 挂错的典型表现是「点开 App 只有一个透明界面/白屏」而不是崩溃。
                VPackage.ActivityComponent c = cache.activities.get(0);
                VPackage.ActivityIntentInfo ii = new VPackage.ActivityIntentInfo();
                ii.filter = launcherFilter;
                c.intents.add(ii);
                VLog.w(TAG, "launcher fallback to FIRST activity: " + c.info.name
                        + " (pkg=" + pi.packageName + ")：manifest 里没扫到 MAIN+LAUNCHER，"
                        + "若点开是透明/空界面，就是这个入口选错了");
            }
            cache.services = new ArrayList<>();
            if (pi.services != null) {
                for (ServiceInfo info : pi.services) {
                    cache.services.add(new VPackage.ServiceComponent(info));
                }
            }
            cache.receivers = new ArrayList<>();
            if (pi.receivers != null) {
                for (ActivityInfo info : pi.receivers) {
                    cache.receivers.add(new VPackage.ActivityComponent(info));
                }
            }
            cache.providers = new ArrayList<>();
            if (pi.providers != null) {
                for (ProviderInfo info : pi.providers) {
                    cache.providers.add(new VPackage.ProviderComponent(info));
                }
            }
            addOwner(cache);
            VLog.d(TAG, "modern parse ok: " + pi.packageName
                    + " launcher=" + launcherClass + " activities=" + cache.activities.size());
            return cache;
        } catch (Throwable t) {
            VLog.w(TAG, "modern parse fail", t);
            return null;
        }
    }

    /** TwinBox 2.1.45：这个类名在不在 APK 的 activity 表里（launcher 候选必须先存在） */
    private static boolean hasActivityNamed(ActivityInfo[] activities, String className) {
        return findActivity(activities, className) != null;
    }

    /** TwinBox 2.1.45：按类名取 APK 里的 ActivityInfo（取不到返回 null） */
    private static ActivityInfo findActivity(ActivityInfo[] activities, String className) {
        if (activities == null || className == null) {
            return null;
        }
        for (ActivityInfo info : activities) {
            if (info != null && className.equals(info.name)) {
                return info;
            }
        }
        return null;
    }

    /**
     * TwinBox 2.1.45：manifest 扫不动时的名字启发式。
     * 只认约定俗成的入口名（MainActivity / LauncherActivity / SplashActivity …），
     * 顺序即优先级；同名多个时优先非透明主题（wxapi 一类回调页几乎都是 Translucent 主题）。
     */
    private static String guessLauncherByName(ActivityInfo[] activities) {
        if (activities == null || activities.length == 0) {
            return null;
        }
        final String[] suffixes = {
                "MainActivity", "LauncherActivity", "SplashActivity", "WelcomeActivity",
                "HomeActivity", "StartActivity", "AppActivity", "EntryActivity",
        };
        for (String suffix : suffixes) {
            String fallback = null;
            for (ActivityInfo info : activities) {
                if (info == null || info.name == null) {
                    continue;
                }
                String simple = info.name.substring(info.name.lastIndexOf('.') + 1);
                if (!suffix.equals(simple)) {
                    continue;
                }
                if (!isTranslucentTheme(info.theme)) {
                    return info.name;
                }
                if (fallback == null) {
                    fallback = info.name;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }

    /** 主题是不是框架的 translucent 系（只对 @android:style/… 有效，App 自定义主题查不到就返回 false） */
    private static boolean isTranslucentTheme(int theme) {
        if (theme == 0) {
            return false;
        }
        try {
            String name = android.content.res.Resources.getSystem().getResourceName(theme);
            return name != null && name.contains("Translucent");
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static VPackage readPackageCache(String packageName) {
        Parcel p = Parcel.obtain();
        try {
            File cacheFile = VEnvironment.getPackageCacheFile(packageName);
            FileInputStream is = new FileInputStream(cacheFile);
            byte[] bytes = FileUtils.toByteArray(is);
            is.close();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            if (p.readInt() != 4) {
                throw new IllegalStateException("Invalid version.");
            }
            VPackage pkg = new VPackage(p);
            addOwner(pkg);
            return pkg;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
        return null;
    }

    public static void readSignature(VPackage pkg) {
        File signatureFile = VEnvironment.getSignatureFile(pkg.packageName);
        if (!signatureFile.exists()) {
            return;
        }
        Parcel p = Parcel.obtain();
        try {
            FileInputStream fis = new FileInputStream(signatureFile);
            byte[] bytes = FileUtils.toByteArray(fis);
            fis.close();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            pkg.mSignatures = p.createTypedArray(Signature.CREATOR);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
    }

    public static void savePackageCache(VPackage pkg) {
        final String packageName = pkg.packageName;
        File cacheFile = VEnvironment.getPackageCacheFile(packageName);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        File signatureFile = VEnvironment.getSignatureFile(packageName);
        if (signatureFile.exists()) {
            signatureFile.delete();
        }
        Parcel p = Parcel.obtain();

        try {
            p.writeInt(4);
            pkg.writeToParcel(p, 0);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(p.marshall());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
        Signature[] signatures = pkg.mSignatures;
        if (signatures != null) {
            if (signatureFile.exists() && !signatureFile.delete()) {
                VLog.w(TAG, "Unable to delete the signatures of " + packageName);
            }
            p = Parcel.obtain();
            try {
                p.writeTypedArray(signatures, 0);
                FileUtils.writeParcelToFile(p, signatureFile);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                p.recycle();
            }
        }
    }

    private static VPackage buildPackageCache(PackageParser.Package p) {
        VPackage cache = new VPackage();
        cache.activities = new ArrayList<>(p.activities.size());
        cache.services = new ArrayList<>(p.services.size());
        cache.receivers = new ArrayList<>(p.receivers.size());
        cache.providers = new ArrayList<>(p.providers.size());
        cache.instrumentation = new ArrayList<>(p.instrumentation.size());
        cache.permissions = new ArrayList<>(p.permissions.size());
        cache.permissionGroups = new ArrayList<>(p.permissionGroups.size());

        for (PackageParser.Activity activity : p.activities) {
            cache.activities.add(new VPackage.ActivityComponent(activity));
        }
        for (PackageParser.Service service : p.services) {
            cache.services.add(new VPackage.ServiceComponent(service));
        }
        for (PackageParser.Activity receiver : p.receivers) {
            cache.receivers.add(new VPackage.ActivityComponent(receiver));
        }
        for (PackageParser.Provider provider : p.providers) {
            cache.providers.add(new VPackage.ProviderComponent(provider));
        }
        for (PackageParser.Instrumentation instrumentation : p.instrumentation) {
            cache.instrumentation.add(new VPackage.InstrumentationComponent(instrumentation));
        }
        for (PackageParser.Permission permission : p.permissions) {
            cache.permissions.add(new VPackage.PermissionComponent(permission));
        }
        for (PackageParser.PermissionGroup permissionGroup : p.permissionGroups) {
            cache.permissionGroups.add(new VPackage.PermissionGroupComponent(permissionGroup));
        }
        cache.requestedPermissions = new ArrayList<>(p.requestedPermissions.size());
        cache.requestedPermissions.addAll(p.requestedPermissions);
        if (mirror.android.content.pm.PackageParser.Package.protectedBroadcasts != null) {
            List<String> protectedBroadcasts = mirror.android.content.pm.PackageParser.Package.protectedBroadcasts.get(p);
            if (protectedBroadcasts != null) {
                cache.protectedBroadcasts = new ArrayList<>(protectedBroadcasts);
                cache.protectedBroadcasts.addAll(protectedBroadcasts);
            }
        }
        cache.applicationInfo = p.applicationInfo;
        cache.mSignatures = getSignature(p);
        cache.mAppMetaData = p.mAppMetaData;
        cache.packageName = p.packageName;
        cache.mPreferredOrder = p.mPreferredOrder;
        cache.mVersionName = p.mVersionName;
        cache.mSharedUserId = p.mSharedUserId;
        cache.mSharedUserLabel = p.mSharedUserLabel;
        cache.usesLibraries = p.usesLibraries;
        cache.mVersionCode = p.mVersionCode;
        cache.configPreferences = p.configPreferences;
        cache.reqFeatures = p.reqFeatures;
        cache.usesOptionalLibraries = p.usesOptionalLibraries;
        addOwner(cache);
        return cache;
    }

    private static Signature[] getSignature(PackageParser.Package p) {
        if (BuildCompat.isPie()) {
            return p.mSigningDetails.signatures;
        } else {
            return p.mSignatures;
        }
    }

    public static void initApplicationInfoBase(PackageSetting ps, VPackage p) {
        ApplicationInfo ai = p.applicationInfo;
        if (TextUtils.isEmpty(ai.processName)) {
            ai.processName = ai.packageName;
        }
        ai.enabled = true;
        ai.uid = ps.appId;
        ai.name = ComponentFixer.fixComponentClassName(ps.packageName, ai.name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ApplicationInfoL.scanSourceDir.set(ai, ai.dataDir);
            ApplicationInfoL.scanPublicSourceDir.set(ai, ai.dataDir);
            String hostPrimaryCpuAbi = ApplicationInfoL.primaryCpuAbi.get(VirtualCore.get().getContext().getApplicationInfo());
            ApplicationInfoL.primaryCpuAbi.set(ai, hostPrimaryCpuAbi);
        }
        String[] sharedLibraryFiles = sSharedLibCache.get(ps.packageName);
        if (sharedLibraryFiles == null) {
            List<String> sharedLibraryFileList = new LinkedList<>();
            if (ps.appMode == InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
                PackageManager hostPM = VirtualCore.get().getUnHookPackageManager();
                try {
                    ApplicationInfo hostInfo = hostPM.getApplicationInfo(ps.packageName, PackageManager.GET_SHARED_LIBRARY_FILES);
                    if (hostInfo.sharedLibraryFiles != null) {
                        Collections.addAll(sharedLibraryFileList, hostInfo.sharedLibraryFiles);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // ignore
                }
            }
            if (Build.VERSION.SDK_INT >= 28 && (ai.targetSdkVersion < 28 || needFixApache(p.usesLibraries, p.usesOptionalLibraries))) {
                String APACHE_LEGACY_JAR = "/system/framework/org.apache.http.legacy.boot.jar";
                String APACHE_LEGACY_JAR_Q = "/system/framework/org.apache.http.legacy.jar";
                if (!sharedLibraryFileList.contains(APACHE_LEGACY_JAR) && !sharedLibraryFileList.contains(APACHE_LEGACY_JAR_Q)) {
                    if (BuildCompat.isQ()) {
                        if (!FileUtils.isExist(APACHE_LEGACY_JAR_Q)) {
                            sharedLibraryFileList.add(APACHE_LEGACY_JAR);
                        } else {
                            sharedLibraryFileList.add(APACHE_LEGACY_JAR_Q);
                        }
                    } else {
                        sharedLibraryFileList.add(APACHE_LEGACY_JAR);
                    }
                }
            }
            sharedLibraryFiles = sharedLibraryFileList.toArray(new String[0]);
            sSharedLibCache.put(ps.packageName, sharedLibraryFiles);
        }
        ai.sharedLibraryFiles = sharedLibraryFiles;
    }

    private static boolean needFixApache(List<String> usesLibraries, List<String> usesOptionalLibraries){
        if(usesLibraries != null){
            if(usesLibraries.contains("org.apache.http.legacy")){
                return true;
            }
        }
        if(usesOptionalLibraries != null){
            if(usesOptionalLibraries.contains("org.apache.http.legacy")){
                return true;
            }
        }
        return false;
    }

    private static boolean initApplicationAsUser(ApplicationInfo ai, int userId) {
        PackageSetting ps = PackageCacheManager.getSetting(ai.packageName);
        if (ps == null) {
            dev.twinbox.app.TLog.w("V|PPE", "initApplicationAsUser: ps NULL for " + ai.packageName);
            return false;
        }
        boolean is64bit = ps.isRunPluginProcess();
        String apkPath = ps.getApkPath(is64bit);
        if(apkPath == null){
            dev.twinbox.app.TLog.w("V|PPE", "initApplicationAsUser: apkPath NULL (is64bit=" + is64bit
                    + ") for " + ai.packageName);
            return false;
        }
        ai.publicSourceDir = apkPath;
        ai.sourceDir = apkPath;
        SettingConfig config = VirtualCore.getConfig();
        SettingConfig.AppLibConfig libConfig = config.getAppLibConfig(ai.packageName);
        if (is64bit) {
            ai.nativeLibraryDir = VEnvironment.getAppLibDirectory64(ai.packageName).getPath();
        } else {
            ai.nativeLibraryDir = VEnvironment.getAppLibDirectory(ai.packageName).getPath();
        }
        if (ps.appMode == InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
            ApplicationInfo outside = null;
            try {
                outside = VirtualCore.get().getUnHookPackageManager().getApplicationInfo(ai.packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // ignore
            }
            if (libConfig == SettingConfig.AppLibConfig.UseRealLib && outside == null) {
                libConfig = SettingConfig.AppLibConfig.UseOwnLib;
            }
            if (GmsSupport.isGoogleAppOrService(ai.packageName)) {
                libConfig = SettingConfig.AppLibConfig.UseOwnLib;
            }
            if (outside != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ai.splitNames = outside.splitNames;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ai.splitPublicSourceDirs = outside.splitPublicSourceDirs;
                    ai.splitSourceDirs = outside.splitSourceDirs;
                }
                if (libConfig == SettingConfig.AppLibConfig.UseRealLib) {
                    String outsideNativeLib = chooseOutsideNativeLib(outside, VirtualRuntime.is64bit());
                    if (outsideNativeLib != null) {
                        ai.nativeLibraryDir = outsideNativeLib;
                    }
                }
            }
        }
        // TwinBox 修复：Android 16 上 hidden API 豁免失败 → RefClass.load 拿不到
        // primaryCpuAbi/secondaryCpuAbi 等 mirror 字段（保持 null）→ .set() 直接 NPE，
        // 异常穿透 generateApplicationInfo/generateActivityInfo 被 binder 层吞掉，
        // 表现为：q2 查询静默返回空、显式 getActivityInfo 静默 null、启动 -1。
        // 全部 mirror 访问必须容错：失败就跳过（这些是可选 ABI 元数据，缺了不影响启动）。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (is64bit) {
                    if (Build.SUPPORTED_64_BIT_ABIS.length > 0
                            && ApplicationInfoL.primaryCpuAbi != null) {
                        ApplicationInfoL.primaryCpuAbi.set(ai, Build.SUPPORTED_64_BIT_ABIS[0]);
                    }
                    if (ps.flag == PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT
                            && ApplicationInfoL.secondaryCpuAbi != null
                            && Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                        ApplicationInfoL.secondaryCpuAbi.set(ai, Build.SUPPORTED_32_BIT_ABIS[0]);
                    }
                } else {
                    // TwinBox 修复：纯 64 位设备（arm64-v8a only）SUPPORTED_32_BIT_ABIS 为空数组，
                    // 直接 [0] 会 AIOOBE（v2.1.3 已被 try-catch 兜住，这里补长度守卫根治）
                    if (ApplicationInfoL.primaryCpuAbi != null
                            && Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                        ApplicationInfoL.primaryCpuAbi.set(ai, Build.SUPPORTED_32_BIT_ABIS[0]);
                    }
                    if (ps.flag == PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT) {
                        if (Build.SUPPORTED_64_BIT_ABIS.length > 0
                                && ApplicationInfoL.secondaryCpuAbi != null) {
                            ApplicationInfoL.secondaryCpuAbi.set(ai, Build.SUPPORTED_64_BIT_ABIS[0]);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            dev.twinbox.app.TLog.w("V|PPE", "cpuAbi mirror fail (hidden API blocked): " + t);
        }

        if (is64bit) {
            ai.dataDir = VEnvironment.getDataUserPackageDirectory64(userId, ai.packageName).getPath();
        } else {
            ai.dataDir = VEnvironment.getDataUserPackageDirectory(userId, ai.packageName).getPath();
        }
        String scanSourceDir = new File(apkPath).getParent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (ApplicationInfoL.scanSourceDir != null) {
                    ApplicationInfoL.scanSourceDir.set(ai, scanSourceDir);
                }
                if (ApplicationInfoL.scanPublicSourceDir != null) {
                    ApplicationInfoL.scanPublicSourceDir.set(ai, scanSourceDir);
                }
            } catch (Throwable t) {
                dev.twinbox.app.TLog.w("V|PPE", "scanSourceDir mirror fail: " + t);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            String deDataDir;
            if (is64bit) {
                deDataDir = VEnvironment.getDeDataUserPackageDirectory64(userId, ai.packageName).getPath();
            } else {
                deDataDir = VEnvironment.getDeDataUserPackageDirectory(userId, ai.packageName).getPath();
            }
            if (ApplicationInfoN.deviceEncryptedDataDir != null) {
                ApplicationInfoN.deviceEncryptedDataDir.set(ai, deDataDir);
            }
            if (ApplicationInfoN.credentialEncryptedDataDir != null) {
                ApplicationInfoN.credentialEncryptedDataDir.set(ai, ai.dataDir);
            }
            if (ApplicationInfoN.deviceProtectedDataDir != null) {
                ApplicationInfoN.deviceProtectedDataDir.set(ai, deDataDir);
            }
            if (ApplicationInfoN.credentialProtectedDataDir != null) {
                ApplicationInfoN.credentialProtectedDataDir.set(ai, ai.dataDir);
            }
        }
        if (config.isEnableIORedirect()) {
            if (config.isUseRealDataDir(ai.packageName)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ai.dataDir = "/data/user/" + userId + "/" + ai.packageName;
                } else {
                    ai.dataDir = "/data/data/" + ai.packageName;
                }
            }
            if (config.isUseRealLibDir(ai.packageName)) {
                ai.nativeLibraryDir = "/data/data/" + ai.packageName + "/lib/";
            }
        }
        return true;
    }

    private static String chooseOutsideNativeLib(ApplicationInfo ai, boolean is64bit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                String primaryCpuAbi = ApplicationInfoL.primaryCpuAbi.get(ai);
                String secondaryCpuAbi = ApplicationInfoL.secondaryCpuAbi.get(ai);
                if (primaryCpuAbi == null) {
                    return null;
                }
                boolean matchPrimary = is64bit
                        ? NativeLibraryHelperCompat.is64bitAbi(primaryCpuAbi)
                        : NativeLibraryHelperCompat.is32bitAbi(primaryCpuAbi);
                if (matchPrimary) {
                    return ai.nativeLibraryDir;
                } else {
                    if (secondaryCpuAbi != null) {
                        return ApplicationInfoL.secondaryNativeLibraryDir.get(ai);
                    }
                    return null;
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return ai.nativeLibraryDir;
    }

    private static void addOwner(VPackage p) {
        for (VPackage.ActivityComponent activity : p.activities) {
            activity.owner = p;
            for (VPackage.ActivityIntentInfo info : activity.intents) {
                info.activity = activity;
            }
        }
        for (VPackage.ServiceComponent service : p.services) {
            service.owner = p;
            for (VPackage.ServiceIntentInfo info : service.intents) {
                info.service = service;
            }
        }
        for (VPackage.ActivityComponent receiver : p.receivers) {
            receiver.owner = p;
            for (VPackage.ActivityIntentInfo info : receiver.intents) {
                info.activity = receiver;
            }
        }
        for (VPackage.ProviderComponent provider : p.providers) {
            provider.owner = p;
            for (VPackage.ProviderIntentInfo info : provider.intents) {
                info.provider = provider;
            }
        }
        for (VPackage.InstrumentationComponent instrumentation : p.instrumentation) {
            instrumentation.owner = p;
        }
        for (VPackage.PermissionComponent permission : p.permissions) {
            permission.owner = p;
        }
        for (VPackage.PermissionGroupComponent group : p.permissionGroups) {
            group.owner = p;
        }
        int flags = ApplicationInfo.FLAG_HAS_CODE;
        if (GmsSupport.isGoogleService(p.packageName)) {
            flags |= ApplicationInfo.FLAG_PERSISTENT;
        }
        p.applicationInfo.flags |= flags;
    }

    private static boolean isAppPermissionEnable(String pkg, String perName) {
        if (!VirtualCore.get().checkSelfPermission(perName, false)) {
            return false;
        }
        return com.xdja.zs.VAppPermissionManagerService.get().getAppPermissionEnable(pkg, perName);
    }

    public static PackageInfo generatePackageInfo(VPackage p, int flags, long firstInstallTime, long lastUpdateTime, PackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        if (p.mSignatures == null) {
            readSignature(p);
        }
        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);
        if(pi.applicationInfo == null){
            return null;
        }
        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (p.requestedPermissions != null && !p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }
        if ((flags & PackageManager.GET_GIDS) != 0) {
            pi.gids = PackageParserCompat.GIDS;
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ActivityComponent a = p.activities.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId);
                }
                pi.activities = res;
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ActivityComponent a = p.receivers.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId);
                }
                pi.receivers = res;
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ServiceComponent s = p.services.get(i);
                    res[num++] = generateServiceInfo(s, flags, state, userId);
                }
                pi.services = res;
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final VPackage.ProviderComponent pr = p.providers.get(i);
                    res[num++] = generateProviderInfo(pr, flags, state, userId);
                }
                pi.providers = res;
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            N = p.requestedPermissions == null ? 0 : p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i = 0; i < N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    pi.requestedPermissionsFlags[i] = isAppPermissionEnable(pi.packageName, perm) ? PackageInfo.REQUESTED_PERMISSION_GRANTED : 0;
                }
            }
        }
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            int N = (p.mSignatures != null) ? p.mSignatures.length : 0;
            if (N > 0) {
                pi.signatures = new Signature[N];
                System.arraycopy(p.mSignatures, 0, pi.signatures, 0, N);
            } else {
                try {
                    PackageInfo outInfo = VirtualCore.get().getUnHookPackageManager().getPackageInfo(p.packageName, PackageManager.GET_SIGNATURES);
                    pi.signatures = outInfo.signatures;
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        return pi;
    }

    public static ApplicationInfo generateApplicationInfo(VPackage p, int flags,
                                                          PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);

        //xdja mdm能否卸载盒内应用需要判断FLAG_SYSTEM标志，龙剑邮箱添加该标志so加载失败，黑龙江项目中无mdm
        if(InstallerSettingManager.get().isSystemApp(p.packageName) && (!p.packageName.equals("com.xdja.HDSafeEMailClient"))){
            ai.flags = ai.flags | ApplicationInfo.FLAG_SYSTEM;
        }

        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }
        if(!initApplicationAsUser(ai, userId)){
            return null;
        }
        if(VirtualCore.getConfig().isForceVmSafeMode(ai.packageName)) {
            ai.flags |= ApplicationInfo.FLAG_VM_SAFE_MODE;
        }
        return ai;
    }


    public static ActivityInfo generateActivityInfo(VPackage.ActivityComponent a, int flags,
                                                    PackageUserState state, int userId) {
        if (a == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (a.metaData != null)) {
            ai.metaData = a.metaData;
        }
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        if(ai.applicationInfo == null){
            return null;
        }
        return ai;
    }

    public static ServiceInfo generateServiceInfo(VPackage.ServiceComponent s, int flags,
                                                  PackageUserState state, int userId) {
        if (s == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        ServiceInfo si = new ServiceInfo(s.info);
        // Make shallow copies so we can store the metadata safely
        if ((flags & PackageManager.GET_META_DATA) != 0 && s.metaData != null) {
            si.metaData = s.metaData;
        }
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        if(si.applicationInfo == null){
            return null;
        }
        return si;
    }

    public static ProviderInfo generateProviderInfo(VPackage.ProviderComponent p, int flags,
                                                    PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(state, flags)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (p.metaData != null)) {
            pi.metaData = p.metaData;
        }

        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        if(pi.applicationInfo == null){
            return null;
        }
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(
            VPackage.InstrumentationComponent i, int flags) {
        if (i == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static PermissionInfo generatePermissionInfo(
            VPackage.PermissionComponent p, int flags) {
        if (p == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static PermissionGroupInfo generatePermissionGroupInfo(
            VPackage.PermissionGroupComponent pg, int flags) {
        if (pg == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pg.info;
        }
        PermissionGroupInfo pgi = new PermissionGroupInfo(pg.info);
        pgi.metaData = pg.metaData;
        return pgi;
    }

    private static boolean checkUseInstalledOrHidden(PackageUserState state, int flags) {
        //noinspection deprecation
        return (state.installed && !state.hidden)
                || (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;
    }

}
