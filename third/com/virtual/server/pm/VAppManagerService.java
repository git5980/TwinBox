package com.lody.virtual.server.pm;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.stub.InstallerSetting;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.DexOptimizer;
import com.lody.virtual.helper.collection.IntArray;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.Singleton;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;
import com.lody.virtual.remote.InstallOptions;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.AttributeCache;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.UidSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.bit64.V64BitHelper;
import com.lody.virtual.server.interfaces.IAppManager;
import com.lody.virtual.server.interfaces.IPackageObserver;
import com.lody.virtual.server.job.VJobSchedulerService;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.pm.parser.PackageParserEx;
import com.lody.virtual.server.pm.parser.VPackage;
import com.xdja.zs.VServiceKeepAliveManager;
import com.xdja.zs.VServiceKeepAliveService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lody.virtual.remote.InstalledAppInfo.MODE_APP_COPY_APK;
import static com.lody.virtual.remote.InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK;


/**
 * @author Lody
 */
public class VAppManagerService extends IAppManager.Stub {

    private static final String TAG = VAppManagerService.class.getSimpleName();
    private static final Singleton<VAppManagerService> sService = new Singleton<VAppManagerService>() {
        @Override
        protected VAppManagerService create() {
            return new VAppManagerService();
        }
    };
    private final UidSystem mUidSystem = new UidSystem();
    private final PackagePersistenceLayer mPersistenceLayer = new PackagePersistenceLayer(this);
    private final Set<String> mVisibleOutsidePackages = new HashSet<>();
    private boolean mBooting;
    private RemoteCallbackList<IPackageObserver> mRemoteCallbackList = new RemoteCallbackList<>();

    /*
        《A》
        该广播接收器监听外部应用安装/卸载，内部随之做相应的动作（外面的卸载，内部也卸载；外部安装，内部随之更新）。
        需要屏蔽掉。
     */
    private BroadcastReceiver appEventReciever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mBooting) {
                return;
            }
            PendingResult result = goAsync();
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            Uri data = intent.getData();
            if (data == null) {
                return;
            }
            String pkg = data.getSchemeSpecificPart();
            if (pkg == null) {
                return;
            }
            PackageSetting ps = PackageCacheManager.getSetting(pkg);
            if (ps == null || ps.appMode != InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
                return;
            }
            VActivityManagerService.get().killAppByPkg(pkg, VUserHandle.USER_ALL);
            if (action.equals(Intent.ACTION_PACKAGE_REPLACED)) {
                ApplicationInfo outInfo = null;
                try {
                    outInfo = VirtualCore.getPM().getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                if (outInfo == null) {
                    return;
                }
                InstallOptions options = InstallOptions.makeOptions(true, false, InstallOptions.UpdateStrategy.FORCE_UPDATE);
                InstallResult res = installPackageImpl(outInfo.publicSourceDir, options);
                VLog.e(TAG, "Update package %s %s", res.packageName, res.isSuccess ? "success" : "failed");
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                    VLog.e(TAG, "Removing package %s...", ps.packageName);
                    uninstallPackageFully(ps, true);
                }
            }
            result.finish();
        }
    };


    public static VAppManagerService get() {
        return sService.get();
    }

    public static void systemReady() {
        VEnvironment.systemReady();
        if (!BuildCompat.isPie()) {
            get().extractRequiredFrameworks();
        }
        get().startup();
    }

    private void startup() {
        mVisibleOutsidePackages.add("com.android.providers.downloads");
        mUidSystem.initUidList();

        //见《A》
        /*IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        VirtualCore.get().getContext().registerReceiver(appEventReciever, filter);*/
    }

    public boolean isBooting() {
        return mBooting;
    }

    private void extractRequiredFrameworks() {
        for (String framework : StubManifest.REQUIRED_FRAMEWORK) {
            File zipFile = VEnvironment.getFrameworkFile32(framework);
            File odexFile = VEnvironment.getOptimizedFrameworkFile32(framework);
            if (!odexFile.exists()) {
                OatHelper.extractFrameworkFor32Bit(framework, zipFile, odexFile);
            }
        }
    }

    @Override
    public void scanApps() {
        if (mBooting) {
            return;
        }
        synchronized (this) {
            mBooting = true;

            /*
                这里将安装过的应用全部加载起来。
             */
            mPersistenceLayer.read();
            if (mPersistenceLayer.changed) {
                mPersistenceLayer.changed = false;
                mPersistenceLayer.save();
                VLog.w(TAG, "Package PersistenceLayer updated.");
            }

            /*
                预安装，目的不明。
             */
            for (String preInstallPkg : SpecialComponentList.getPreInstallPackages()) {
                if (!isAppInstalled(preInstallPkg)) {
                    try {
                        ApplicationInfo outInfo = VirtualCore.get().getUnHookPackageManager().getApplicationInfo(preInstallPkg, 0);
                        InstallOptions options = InstallOptions.makeOptions(true, false, InstallOptions.UpdateStrategy.FORCE_UPDATE);
                        installPackageImpl(outInfo.publicSourceDir, options);
                    } catch (PackageManager.NameNotFoundException e) {
                        // ignore
                    }
                }
            }

            /*
                这个不明白 ***
             */
            PrivilegeAppOptimizer.get().performOptimizeAllApps();
            //适配旧版本va，或者系统升级
            if(isAppInstalled(InstallerSetting.PROVIDER_TELEPHONY_PKG)){
                supportTelephony(0);
            }

            mBooting = false;
        }

        // TwinBox 修复：存量 userState 自愈。
        // <=2.1.1 的 bug：isUpdate 安装不写 userState，持久化层里已有
        // installed=false 的死记录（装完查询 0 / 启动 -1）。引擎起来后统一修复 user 0，
        // 否则用户必须卸载重装才能救活。仅修 user 0（本容器单用户语义），
        // 且不动 hidden（尊重显式隐藏），只补 installed。
        synchronized (this) {
            boolean healed = false;
            for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null && !ps.isInstalled(0)) {
                    ps.setInstalled(0, true);
                    healed = true;
                    VLog.w(TAG, "healed userState(0) for %s", ps.packageName);
                    dev.twinbox.app.TLog.w("V|VAMS", "heal userState(0): " + ps.packageName);
                }
            }
            if (healed) {
                mPersistenceLayer.save();
            }
        }
    }

    private void cleanUpResidualFiles(PackageSetting ps) {
        VLog.e(TAG, "cleanup residual files for : %s", ps.packageName);
        uninstallPackageFully(ps, false);
    }


    public void onUserCreated(VUserInfo userInfo) {
        VEnvironment.getUserDataDirectory(userInfo.id).mkdirs();
    }


    synchronized boolean loadPackage(PackageSetting setting) {
        if (!loadPackageInnerLocked(setting)) {
            cleanUpResidualFiles(setting);
            return false;
        }
        return true;
    }

    private boolean loadPackageInnerLocked(PackageSetting ps) {
        boolean modeUseOutsideApk = ps.appMode == InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK;
        if (modeUseOutsideApk) {
            if (!VirtualCore.get().isOutsideInstalled(ps.packageName)) {
                return false;
            }
        }
        VPackage pkg = null;
        try {
            pkg = PackageParserEx.readPackageCache(ps.packageName);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (pkg == null || pkg.packageName == null) {
            //重新解析apk
            File apkFile = VEnvironment.getPackageResourcePath(ps.packageName);
            if (!apkFile.exists()) {
                VLog.e(TAG, "parse failed 1:not found apk %s", ps.packageName);
                return false;
            }
            try {
                //reparse apk
                pkg = PackageParserEx.parsePackage(apkFile);
                VLog.e(TAG, "reload parsePackage ok %s", ps.packageName);
            } catch (Throwable e2) {
                VLog.e(TAG, "parsePackage %s\n%s", ps.packageName, VLog.getStackTraceString(e2));
                return false;
            }
            //save pkg
            if (pkg == null || pkg.packageName == null) {
                //parse failed.
                return false;
            }
            //save cache
            PackageParserEx.savePackageCache(pkg);
        }
        PackageCacheManager.put(pkg, ps);
        if (modeUseOutsideApk) {
            try {
                PackageInfo outInfo = VirtualCore.get().getUnHookPackageManager().getPackageInfo(ps.packageName, 0);
                if (pkg.mVersionCode != outInfo.versionCode) {
                    VLog.d(TAG, "app (" + ps.packageName + ") has changed version, update it.");
                    InstallOptions options = InstallOptions.makeOptions(true, false, InstallOptions.UpdateStrategy.FORCE_UPDATE);
                    installPackageImpl(outInfo.applicationInfo.publicSourceDir, options, true);
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                return false;
            }
        } else {
            VEnvironment.chmodPackageDictionary(new File(ps.getApkPath(ps.isRunPluginProcess())));
        }

        BroadcastSystem.get().startApp(pkg);

        return true;
    }

    @Override
    public boolean isOutsidePackageVisible(String pkg) {
        return pkg != null && mVisibleOutsidePackages.contains(pkg);
    }

    @Override
    public int getUidForSharedUser(String sharedUserName) {
        if (sharedUserName == null) {
            return -1;
        }
        return mUidSystem.getUid(sharedUserName);
    }

    @Override
    public void addVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.add(pkg);
        }
    }

    @Override
    public void removeVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.remove(pkg);
        }
    }

    @Override
    public void installPackage(String path, InstallOptions options, ResultReceiver receiver) {
        InstallResult res;
        synchronized (this) {
            res = installPackageImpl(path, options);
        }
        if (receiver != null) {
            android.os.Bundle data = new Bundle();
            data.putParcelable("result", res);
            receiver.send(0, data);
        }
    }

    @Override
    public void requestCopyPackage64(String packageName) {
        /**
         * Lock VAMS avoid two process invoke this method Simultaneously.
         */
        synchronized (VActivityManagerService.get()) {
            PackageSetting ps = PackageCacheManager.getSetting(packageName);
            if (ps != null && ps.appMode == MODE_APP_USE_OUTSIDE_APK) {
                V64BitHelper.copyPackage64(ps.getApkPath(false), packageName);
            }
        }
    }

    public InstallResult installPackage(String path, InstallOptions options) {
        synchronized (this) {
            return installPackageImpl(path, options);
        }
    }

    private InstallResult installPackageImpl(String path, InstallOptions options){
        return installPackageImpl(path, options, false);
    }

    private InstallResult installPackageImpl(String path, InstallOptions options, boolean loadingApp) {
        long installTime = System.currentTimeMillis();
        if (path == null) {
            return InstallResult.makeFailure("path = NULL");
        }
        File packageFile = new File(path);
        if (!packageFile.exists() || !packageFile.isFile()) {
            return InstallResult.makeFailure("Package File is not exist.");
        }
        VPackage pkg = null;
        try {
            pkg = PackageParserEx.parsePackage(packageFile);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (pkg == null || pkg.packageName == null) {
            return InstallResult.makeFailure("Unable to parse the package.");
        }
        if(!loadingApp) {
            BroadcastSystem.get().stopApp(pkg.packageName);
        }
        AttributeCache.instance().removePackage(pkg.packageName);
        VActivityManagerService.get().killAppByPkg(pkg.packageName, -1);
        InstallResult res = new InstallResult();
        res.packageName = pkg.packageName;
        // PackageCache holds all packages, try to check if we need to update.
        VPackage existOne = PackageCacheManager.get(pkg.packageName);
        PackageSetting existSetting = existOne != null ? (PackageSetting) existOne.mExtras : null;
        if (existOne != null) {
            if (options.updateStrategy == InstallOptions.UpdateStrategy.IGNORE_NEW_VERSION) {
                res.isUpdate = true;
                return res;
            }
            if (!isAllowedUpdate(existOne, pkg, options.updateStrategy)) {
                res.error = "Not allowed to update the package.";
                return res;
            }
            res.isUpdate = true;
            VServiceKeepAliveService.get().scheduleUpdateKeepAliveList(res.packageName, VServiceKeepAliveManager.ACTION_TEMP_DEL);
            VActivityManagerService.get().killAppByPkg(res.packageName, VUserHandle.USER_ALL);
        }
        boolean useSourceLocationApk = options.useSourceLocationApk;
        if (existOne != null) {
            PackageCacheManager.remove(pkg.packageName);
        }
        PackageSetting ps;
        if (existSetting != null) {
            ps = existSetting;
        } else {
            ps = new PackageSetting();
        }
        boolean support64bit = false, support32bit = false;
        boolean checkSupportAbi = true;
        if (!GmsSupport.GMS_PKG.equals(pkg.packageName) && GmsSupport.isGoogleAppOrService(pkg.packageName)) {
            PackageSetting gmsPs = PackageCacheManager.getSetting(GmsSupport.GMS_PKG);
            if (gmsPs != null) {
                ps.flag = gmsPs.flag;
                support32bit = isPackageSupport32Bit(ps);
                support64bit = isPackageSupport64Bit(ps);
                checkSupportAbi = false;
            }
        }
        Map<String, List<String>> soMap = null;

        if (checkSupportAbi) {
            soMap = NativeLibraryHelperCompat.getSoMapForApk(packageFile.getPath());
            Set<String> abiList = soMap.keySet();
            if (abiList.isEmpty()) {
                support32bit = true;
                support64bit = true;
            } else {
                if (NativeLibraryHelperCompat.contain64bitAbi(abiList)) {
                    support64bit = true;
                }
                if (NativeLibraryHelperCompat.contain32bitAbi(abiList)) {
                    support32bit = true;
                }
            }
            if (support32bit) {
                if (support64bit) {
                    ps.flag = PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT;
                } else {
                    ps.flag = PackageSetting.FLAG_RUN_32BIT;
                }
            } else {
                ps.flag = PackageSetting.FLAG_RUN_64BIT;
            }
        }
        if ((VirtualRuntime.is64bit() && ps.flag == PackageSetting.FLAG_RUN_32BIT)
                || (!VirtualRuntime.is64bit() && ps.flag == PackageSetting.FLAG_RUN_64BIT)) {
            if (!VirtualCore.get().is64BitEngineInstalled() || !V64BitHelper.has64BitEngineStartPermission()) {
                return InstallResult.makeFailure("64bit engine not installed.");
            }
        }
        NativeLibraryHelperCompat.copyNativeBinaries(packageFile, VEnvironment.getAppLibDirectory(pkg.packageName), soMap);

        if (!useSourceLocationApk) {
            //old apk
            File oldPackageFile = VEnvironment.getPackageResourcePath(pkg.packageName);
            //new apk
            File privatePackageFile = VEnvironment.getPackageResourcePathNext(pkg.packageName);
            //base.apk
            File baseApkFile = VEnvironment.getPublicResourcePath(pkg.packageName);
            try {
                //delete old odex
                FileUtils.deleteDir(VEnvironment.getOdexFile(pkg.packageName));
                //delete old apk
                FileUtils.deleteDir(oldPackageFile);
                //copy apk -> new apk
                FileUtils.copyFile(packageFile, privatePackageFile);
                VLog.d(TAG, "copyFile:%s->%s", packageFile.getPath(), privatePackageFile.getPath());
            } catch (Exception e) {
                privatePackageFile.delete();
                return InstallResult.makeFailure("Unable to copy the package file.");
            }
            try{
                String realPath = privatePackageFile.getPath();
                String linkPath = baseApkFile.getPath();
                if(!TextUtils.equals(realPath, linkPath)) {
                    //delete base.apk
                    FileUtils.deleteDir(baseApkFile);
                    //new apk->base.apk
                    FileUtils.createSymlink(realPath, linkPath);
                    VLog.d(TAG, "createSymlink:%s->%s", realPath, linkPath);
                }else{
                    VLog.d(TAG, "use base don't need link %s", realPath);
                }
            } catch (Exception e) {
                try {
                    //copy new apk->base.apk
                    FileUtils.copyFile(privatePackageFile, baseApkFile);
                } catch (IOException ex) {
                    baseApkFile.delete();
                    return InstallResult.makeFailure("Unable to copy the package file.");
                }
            }
            packageFile = privatePackageFile;
            VEnvironment.chmodPackageDictionary(packageFile);
        }

        if (support64bit && !useSourceLocationApk) {
            V64BitHelper.copyPackage64(packageFile.getPath(), pkg.packageName);
        }

        ps.appMode = useSourceLocationApk ? MODE_APP_USE_OUTSIDE_APK : MODE_APP_COPY_APK;
        ps.packageName = pkg.packageName;
        ps.appId = VUserHandle.getAppId(mUidSystem.getOrCreateUid(pkg));
        if (res.isUpdate) {
            ps.lastUpdateTime = installTime;
        } else {
            ps.firstInstallTime = installTime;
            ps.lastUpdateTime = installTime;
        }
        // TwinBox 修复：更新安装也必须刷新 userState。
        // 旧代码只在「新装」分支写 userState；覆盖装/残留 ps 走 isUpdate 分支时
        // userState 完全不写 → installed=false → 装完查询 0 结果、启动 -1（静默死锁）。
        // 重装语义本就应该是「对所有已存在用户恢复 installed」。
        for (int userId : VUserManagerService.get().getUserIds()) {
            dev.twinbox.app.TLog.i("V|VAMS", "setUserState pkg=" + ps.packageName
                    + " userId=" + userId + " isUpdate=" + res.isUpdate);
            ps.setUserState(userId, ps.readUserState(userId).launched,
                    ps.readUserState(userId).hidden, userId == 0);
        }
        PackageParserEx.savePackageCache(pkg);
        PackageCacheManager.put(pkg, ps);
        mPersistenceLayer.save();
        if (support32bit && !useSourceLocationApk) {
            try {
                DexOptimizer.optimizeDex(packageFile.getPath(), VEnvironment.getOdexFile(ps.packageName).getPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (options.notify) {
            notifyAppInstalled(ps, -1);
            if(res.isUpdate){
                notifyAppUpdate(ps, -1);
            }
        }
        //版本差异适配
        if (InstallerSetting.PROVIDER_TELEPHONY_PKG.equals(pkg.packageName)) {
            supportTelephony(0);
        }
        if(!loadingApp) {
            BroadcastSystem.get().startApp(pkg);
        }
        res.isSuccess = true;
        VServiceKeepAliveService.get().scheduleUpdateKeepAliveList(res.packageName, VServiceKeepAliveManager.ACTION_TEMP_ADD);
        if(!res.isUpdate) {
            VirtualCore.getConfig().onFirstInstall(ps.packageName, false);
        }
        return res;
    }

    private void supportTelephony(int userId) {
        if (Build.VERSION.SDK_INT >= 29) {
            setDefaultComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.RcsProvider"), userId);
        } else {
            disableComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.RcsProvider"), userId);
        }
        if (Build.VERSION.SDK_INT < 28) {
            disableComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.CarrierIdProvider"), userId);
            disableComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.CarrierProvider"), userId);
        } else {
            setDefaultComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.CarrierIdProvider"), userId);
            setDefaultComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.CarrierProvider"), userId);
        }
        if (Build.VERSION.SDK_INT < 26) {
            disableComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.ServiceStateProvider"), userId);
        } else {
            setDefaultComponent(new ComponentName(InstallerSetting.PROVIDER_TELEPHONY_PKG, "com.android.providers.telephony.ServiceStateProvider"), userId);
        }
    }

    private void disableComponent(ComponentName componentName, int userId){
        VPackageManagerService.get().setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP, userId);
    }

    private void setDefaultComponent(ComponentName componentName, int userId){
        VPackageManagerService.get().setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP, userId);
    }

    @Override
    public synchronized boolean installPackageAsUser(int userId, String packageName) {
        if (VUserManagerService.get().exists(userId)) {
            PackageSetting ps = PackageCacheManager.getSetting(packageName);
            if (ps != null) {
                if (!ps.isInstalled(userId)) {
                    ps.setInstalled(userId, true);
                    mkdirsForUser(packageName, userId);
                    notifyAppInstalled(ps, userId);
                    mPersistenceLayer.save();
                    //版本差异适配
                    if (InstallerSetting.PROVIDER_TELEPHONY_PKG.equals(packageName)) {
                        supportTelephony(userId);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAllowedUpdate(VPackage existOne, VPackage newOne, InstallOptions.UpdateStrategy strategy) {
        switch (strategy) {
            case FORCE_UPDATE:
                return true;
            case COMPARE_VERSION:
                return existOne.mVersionCode <= newOne.mVersionCode;
            case TERMINATE_IF_EXIST:
                return false;
        }
        return true;
    }


    @Override
    public synchronized boolean uninstallPackage(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            uninstallPackageFully(ps, true);
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean uninstallPackageAsUser(String packageName, int userId) {
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            int[] userIds = getPackageInstalledUsers(packageName);
            if (!ArrayUtils.contains(userIds, userId)) {
                return false;
            }
            if (userIds.length == 1) {
                uninstallPackageFully(ps, true);
            } else {
                VServiceKeepAliveService.get().scheduleUpdateKeepAliveList(packageName, VServiceKeepAliveManager.ACTION_DEL);
                // Just hidden it
                VNotificationManagerService.get().cancelAllNotification(packageName, userId);
                VJobSchedulerService.get().cancelAll(ps.appId, userId);
                VActivityManagerService.get().killAppByPkg(packageName, userId);
                ps.setInstalled(userId, false);
                mPersistenceLayer.save();
                deletePackageDataAsUser(userId, ps, false);
                notifyAppUninstalled(ps, userId);
            }
            return true;
        }
        return false;
    }

    private boolean isPackageSupport32Bit(PackageSetting ps) {
        return ps.flag == PackageSetting.FLAG_RUN_32BIT
                || ps.flag == PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT;
    }

    private boolean isPackageSupport64Bit(PackageSetting ps) {
        return ps.flag == PackageSetting.FLAG_RUN_64BIT
                || ps.flag == PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT;
    }

    private void deletePackageDataAsUser(int userId, PackageSetting ps, boolean linkLib) {
        // TwinBox 2.1.50：数据目录删除不再按 32/64 位宽门控。
        // 根因（用户实测：容器内卸载后重装，数据还在）：
        // TwinBox 是单引擎宿主——64 位数据根 ROOT64 实际指向
        // /data/data/dev.twinbox.app64/virtual（不存在的第二宿主目录），
        // guest 数据无论位宽都落在 32 位路径 virtual/data/user/<id>/<pkg>。
        // 纯 64 位应用 flag=FLAG_RUN_64BIT 时 isPackageSupport32Bit=false，
        // 原实现把实际数据所在目录的删除整块跳过，64 位桥删的又是不存在的目录
        // ——结果谁都没删。位宽只决定 native lib 的存放位置，数据目录删除是
        // 幂等操作（目录不存在 deleteDir 即 no-op），一律执行。
        // 同时补上 user_de（DE 存储）目录：上游连 32 位包都没删过这个变体。
        String libPath = VEnvironment.getAppLibDirectory(ps.packageName).getAbsolutePath();
        if (userId == -1) {
            List<VUserInfo> userInfos = VUserManager.get().getUsers();
            if (userInfos != null) {
                for (VUserInfo info : userInfos) {
                    FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(info.id, ps.packageName));
                    FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(info.id, ps.packageName));
                    // 64 位路径变体：单引擎下通常不存在，deleteDir 幂等；
                    // 防未来双引擎场景（64 位目录真被使用时）漏删。
                    FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory64(info.id, ps.packageName));
                    FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory64(info.id, ps.packageName));
                    if(linkLib) {
                        // TwinBox 2.1.50：上游此处用 userId（=-1）建 lib 目录是错的，
                        // 循环变量才是真实用户 id。
                        File userLibDir = VEnvironment.getUserAppLibDirectory(info.id, ps.packageName);
                        if (!userLibDir.exists()) {
                            try {
                                FileUtils.createSymlink(libPath, userLibDir.getPath());
                                VLog.d(TAG, "createSymlink %s@%d's lib", ps.packageName, info.id);
                            } catch (Exception e) {
                                //ignore
                            }
                        }
                    }
                    // add by lml@xdja.com
                    {
                        FileUtils.deleteDir(VEnvironment.getExternalStorageAppDataDir(info.id, ps.packageName));
                    }
                }
            }
        } else {
            FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, ps.packageName));
            FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory(userId, ps.packageName));
            FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory64(userId, ps.packageName));
            FileUtils.deleteDir(VEnvironment.getDeDataUserPackageDirectory64(userId, ps.packageName));
            if(linkLib) {
                File userLibDir = VEnvironment.getUserAppLibDirectory(userId, ps.packageName);
                if (!userLibDir.exists()) {
                    try {
                        FileUtils.createSymlink(libPath, userLibDir.getPath());
                        VLog.d(TAG, "createSymlink %s@%d's lib", ps.packageName, userId);
                    } catch (Exception e) {
                        //ignore
                    }
                }
            }
            // add by lml@xdja.com
            {
                FileUtils.deleteDir(VEnvironment.getExternalStorageAppDataDir(userId, ps.packageName));
            }
        }
        if (isPackageSupport64Bit(ps)) {
            V64BitHelper.cleanPackageData64(userId, ps.packageName);
        }
        // TwinBox 2.1.56：guest 的「sdcard 视图」残留清理。
        // 实锤（真机截图）：guest 的共享存储落点有两套——
        //   ① user/<userId>/<realUserId>/（IO 重定向的历史落点，实测 .android、
        //      123云盘、.FileManagerRecycler 等 sdcard 内容全在这里）；
        //   ② storage/emulated/<userId>/（vs 语义路径，getExternalStorageAppDataDir
        //      覆盖的 Android/data/<pkg> 只删过这里）。
        // 卸载原来只删包数据目录，sdcard 视图里的 app 专属内容（Android/data/<pkg>、
        // 裸包名目录、dot 变体目录）全数残留。
        // 清理规则（两个落点根都扫）：
        //   Android/data|obb|media/<pkg>（规范外存路径）+ <pkg>（裸名）+ .<pkg>（dot 变体）
        // 不删 sdcard 根下的其他名字（123云盘这类 app 自定义名无法枚举归属，
        // 删错会伤共享内容——明确放弃，边界如实记录）。
        String pkg = ps.packageName;
        // Android 单用户真身恒 0（guest 跑在宿主 uid 下，realUserId()=0）；
        // 分身 userId 只影响 user/<id>/ 外层目录，sdcard 视图的内层 real 恒为 0。
        final int realUserId = 0;
        java.io.File[] sdRoots;
        if (userId == -1) {
            List<VUserInfo> allUsers = VUserManager.get().getUsers();
            sdRoots = new java.io.File[(allUsers != null ? allUsers.size() : 0) * 2];
            int k = 0;
            if (allUsers != null) {
                for (VUserInfo info : allUsers) {
                    sdRoots[k++] = new java.io.File(VEnvironment.getUserDataDirectory(info.id), String.valueOf(realUserId));
                    sdRoots[k++] = VEnvironment.getExternalStorageDirectory(info.id);
                }
            }
        } else {
            sdRoots = new java.io.File[]{
                    new java.io.File(VEnvironment.getUserDataDirectory(userId), String.valueOf(realUserId)),
                    VEnvironment.getExternalStorageDirectory(userId),
            };
        }
        String[] subPaths = {
                "Android/data/" + pkg, "Android/obb/" + pkg, "Android/media/" + pkg,
                pkg, "." + pkg,
        };
        for (java.io.File sdRoot : sdRoots) {
            if (sdRoot == null || !sdRoot.exists()) {
                continue;
            }
            for (String sub : subPaths) {
                FileUtils.deleteDir(new java.io.File(sdRoot, sub));
            }
        }
        VNotificationManagerService.get().cancelAllNotification(ps.packageName, userId);
        if(userId == 0 || userId == -1) {
            VirtualCore.getConfig().onFirstInstall(ps.packageName, true);
        }
    }

    public boolean cleanPackageData(String pkg, int userId) {
        PackageSetting ps = PackageCacheManager.getSetting(pkg);
        if (ps == null) {
            return false;
        }
        VActivityManagerService.get().killAppByPkg(pkg, userId);
        deletePackageDataAsUser(userId, ps, true);
        return true;
    }

    private void uninstallPackageFully(PackageSetting ps, boolean notify) {
        String packageName = ps.packageName;
        // TwinBox 2.1.5：加调用方进程身份（16:02 会话出现过用户未操作、无 guest 时的
        // 幽灵自动卸载——binder 真实 callingPid 是定位元凶的唯一硬证据）
        int callingPidRaw = android.os.Binder.getCallingPid();
        int callingUidRaw = android.os.Binder.getCallingUid();
        dev.twinbox.app.TLog.e("V|VAMS", "UNINSTALL triggered: " + packageName
                + " notify=" + notify + " callerPid=" + callingPidRaw
                + " callerUid=" + callingUidRaw
                + " (serverPid=" + android.os.Process.myPid() + ")",
                new Exception("uninstall caller stack"));
        try {
            AttributeCache.instance().removePackage(packageName);
            BroadcastSystem.get().stopApp(packageName);
            VServiceKeepAliveService.get().scheduleUpdateKeepAliveList(packageName, VServiceKeepAliveManager.ACTION_DEL);
            VJobSchedulerService.get().cancelAll(ps.appId, VUserHandle.USER_ALL);
            VNotificationManagerService.get().cancelAllNotification(packageName, VUserHandle.USER_ALL);
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL);
            // TwinBox 2.1.50：APK 资源/data-app 目录删除同样不再按位宽门控
            // （单引擎下这些目录就是实际存储位置；deleteDir 幂等）。
            FileUtils.deleteDir(VEnvironment.getPackageResourcePath(packageName));
            FileUtils.deleteDir(VEnvironment.getPublicResourcePath(packageName));
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName));
            VEnvironment.getOdexFile(packageName).delete();
            for (int id : VUserManagerService.get().getUserIds()) {
                deletePackageDataAsUser(id, ps, false);
            }
            if (isPackageSupport64Bit(ps)) {
                V64BitHelper.uninstallPackage64(-1, packageName);
            }
            PackageCacheManager.remove(packageName);
            mPersistenceLayer.save();
            File cacheFile = VEnvironment.getPackageCacheFile(packageName);
            cacheFile.delete();
            File signatureFile = VEnvironment.getSignatureFile(packageName);
            signatureFile.delete();
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (notify) {
                notifyAppUninstalled(ps, -1);
            }
        }
    }

    @Override
    public int[] getPackageInstalledUsers(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            IntArray installedUsers = new IntArray(5);
            int[] userIds = VUserManagerService.get().getUserIds();
            for (int userId : userIds) {
                if (ps.readUserState(userId).installed) {
                    installedUsers.add(userId);
                }
            }
            return installedUsers.getAll();
        }
        return new int[0];
    }

    @Override
    public List<InstalledAppInfo> getInstalledApps(int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            infoList.add(setting.getAppInfo());
        }
        return infoList;
    }

    @Override
    public List<InstalledAppInfo> getInstalledAppsAsUser(int userId, int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            boolean visible = setting.isInstalled(userId);
            if ((flags & VirtualCore.GET_HIDDEN_APP) == 0 && setting.isHidden(userId)) {
                visible = false;
            }
            if (visible) {
                infoList.add(setting.getAppInfo());
            }
        }
        return infoList;
    }

    @Override
    public int getInstalledAppCount() {
        return PackageCacheManager.PACKAGE_CACHE.size();
    }

    @Override
    public boolean isAppInstalled(String packageName) {
        return packageName != null && PackageCacheManager.PACKAGE_CACHE.containsKey(packageName);
    }

    @Override
    public boolean isAppInstalledAsUser(int userId, String packageName) {
        if (packageName == null || !VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) {
            return false;
        }
        return setting.isInstalled(userId);
    }

    private void notifyAppInstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(0, pkg);

                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if(userId == -1){
            userId = VUserHandle.USER_OWNER;
        }
        sendInstalledBroadcast(pkg, new VUserHandle(userId));
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }

    private void notifyAppUpdate(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUpdate(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUpdateAsUser(0, pkg);

                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUpdateAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if(userId == -1){
            userId = VUserHandle.USER_OWNER;
        }
        sendUpdateBroadcast(pkg, new VUserHandle(userId));
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }

    private void notifyAppUninstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(0, pkg);
                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if(userId == -1){
            userId = VUserHandle.USER_OWNER;
        }
        sendUninstalledBroadcast(pkg, new VUserHandle(userId));
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }


    public void sendInstalledBroadcast(String packageName, VUserHandle user) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, user);
    }

    public void sendUninstalledBroadcast(String packageName, VUserHandle user) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, user);
    }

    public void sendUpdateBroadcast(String packageName, VUserHandle user) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REPLACED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, user);
    }

    @Override
    public void registerObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.register(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unregisterObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.unregister(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public InstalledAppInfo getInstalledAppInfo(String packageName, int flags) {
        synchronized (PackageCacheManager.class) {
            if (packageName != null) {
                PackageSetting setting = PackageCacheManager.getSetting(packageName);
                if (setting != null) {
                    return setting.getAppInfo();
                }
            }
            return null;
        }
    }

    @Override
    public boolean isRun64BitProcess(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        return ps != null && ps.isRunPluginProcess();
    }

    @Override
    public synchronized boolean isIORelocateWork() {
        return true;
    }

    public boolean isPackageLaunched(int userId, String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        return ps != null && ps.isLaunched(userId);
    }

    public void setPackageHidden(int userId, String packageName, boolean hidden) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null && VUserManagerService.get().exists(userId)) {
            ps.setHidden(userId, hidden);
            mPersistenceLayer.save();
        }
    }

    public int getAppId(String packageName) {
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        return setting != null ? setting.appId : -1;
    }

    void restoreFactoryState() {
        VLog.w(TAG, "Warning: Restore the factory state...");
        VEnvironment.getDalvikCacheDirectory().delete();
        VEnvironment.getUserSystemDirectory().delete();
        VEnvironment.getUserDeSystemDirectory().delete();
        VEnvironment.getDataAppDirectory().delete();
    }

    public void savePersistenceData() {
        mPersistenceLayer.save();
    }

    public boolean is64BitUid(int uid) throws PackageManager.NameNotFoundException {
        int appId = VUserHandle.getAppId(uid);
        synchronized (PackageCacheManager.PACKAGE_CACHE) {
            for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps.appId == appId) {
                    return ps.isRunPluginProcess();
                }
            }
        }
        throw new PackageManager.NameNotFoundException();
    }

    private void mkdirsForUser(String packageName, int userId){
        File dir = VEnvironment.getDataUserPackageDirectory(userId, packageName);
        File files = new File(dir, "files");
        File cache = new File(dir, "cache");
        if(!files.exists()){
            files.mkdirs();
        }
        if(!cache.exists()){
            cache.mkdirs();
        }
    }
}
