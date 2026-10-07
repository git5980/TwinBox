package com.lody.virtual.server.am;

import android.Manifest;
import android.app.ActivityManager;
import android.app.IStopUserCallback;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.content.res.Configuration;

import com.lody.virtual.client.IVClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.ipc.ProviderCall;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.helper.compat.ActivityManagerCompat;
import com.lody.virtual.helper.compat.ApplicationThreadCompat;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.compat.PermissionCompat;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.Singleton;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.AppRunningProcessInfo;
import com.lody.virtual.remote.AppTaskInfo;
import com.lody.virtual.remote.BadgerInfo;
import com.lody.virtual.remote.BroadcastIntentData;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.IntentSenderData;
import com.lody.virtual.remote.PendingResultData;
import com.lody.virtual.remote.ServiceResult;
import com.lody.virtual.remote.VParceledListSlice;
import com.lody.virtual.server.bit64.V64BitHelper;
import com.lody.virtual.server.interfaces.IActivityManager;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.pm.PackageCacheManager;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.VPackageManagerService;
import com.xdja.activitycounter.ActivityCounterManager;
import com.xdja.call.PhoneCallService;
import com.xdja.zs.VServiceKeepAliveService;
import com.xdja.zs.controllerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mirror.android.app.PendingIntentJBMR2;
import mirror.android.app.PendingIntentO;

/**
 * @author Lody
 */
public class VActivityManagerService extends IActivityManager.Stub {

    private static final Singleton<VActivityManagerService> sService = new Singleton<VActivityManagerService>() {
        @Override
        protected VActivityManagerService create() {
            return new VActivityManagerService();
        }
    };
    private static final String TAG = VActivityManagerService.class.getSimpleName();
    private final Object mProcessLock = new Object();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final ActivityStack mActivityStack = new ActivityStack(this);
    private final ProcessMap<ProcessRecord> mProcessNames = new ProcessMap<>();
    private final Map<IBinder, IntentSenderData> mIntentSenderMap = new HashMap<>();
    private NotificationManager nm = (NotificationManager) VirtualCore.get().getContext()
            .getSystemService(Context.NOTIFICATION_SERVICE);
    private final Map<String, Boolean> sIdeMap = new HashMap<>();
    private boolean mResult;
    private static boolean CANCEL_ALL_NOTIFICATION_BY_KILL_APP = true;
    private static boolean mDarkMode;
    private long lastBackHomeTime;

    //xdja
    private ActivityManager am = (ActivityManager) VirtualCore.get().getContext()
                                 .getSystemService(Context.ACTIVITY_SERVICE);

    public static VActivityManagerService get() {
        return sService.get();
    }

    public static void systemReady() {
        // TwinBox 2.1.42：引擎启动扫尾——补封 o-stop 团灭/崩溃留下的明文残余。
        // 后台线程 + 延迟：不阻塞引擎就绪（扫尾不急于这几秒）；仅服务进程执行
        // （此时进程表在 :x，主进程调用 systemReady 也不该碰密封——isServerProcess
        // 判定，主进程里进程表是空的话会把活 guest 的数据误封，那个必须防）。
        if (VirtualCore.get().isServerProcess()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignore) {
                        return;
                    }
                    com.lody.virtual.server.seal.SealedStorage.sweepAll();
                }
            }, "TB-seal-sweep").start();
        }
        int mode = VirtualCore.get().getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mDarkMode = mode == Configuration.UI_MODE_NIGHT_YES;
        VirtualCore.get().getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                boolean useAutoDark = mode == Configuration.UI_MODE_NIGHT_YES;
                if(mDarkMode != useAutoDark){
//                    finish all
                    mDarkMode = useAutoDark;
                    VirtualCore.getConfig().onDarkModeChange(mDarkMode);
                }
            }
        }, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
    }

    @Override
    public int startActivity(Intent intent, ActivityInfo info, IBinder resultTo, Bundle options, String resultWho, int requestCode, int userId) {
        synchronized (this) {
            return mActivityStack.startActivityLocked(userId, intent, info, resultTo, options, resultWho, requestCode, VBinder.getCallingUid(), VBinder.getCallingPid());
        }
    }

    @Override
    public boolean finishActivityAffinity(int userId, IBinder token) {
        synchronized (this) {
            return mActivityStack.finishActivityAffinity(userId, token);
        }
    }

    @Override
    public int startActivities(Intent[] intents, String[] resolvedTypes, IBinder token, Bundle options, int userId) {
        synchronized (this) {
            ActivityInfo[] infos = new ActivityInfo[intents.length];
            for (int i = 0; i < intents.length; i++) {
                ActivityInfo ai = VirtualCore.get().resolveActivityInfo(intents[i], userId);
                if (ai == null) {
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                infos[i] = ai;
            }
            // TwinBox 2.1.32：guest 有界面在跑 → 挂后台保活前台服务，
            // 防止 OEM 的 o-stop 把 main/:x/:pN 整组进程连带杀掉
            // （后果就是 guest 后台音乐播放被掐断，表现为「没声音」）。
            dev.twinbox.app.KeepAliveService.start(VirtualCore.get().getContext());
            return mActivityStack.startActivitiesLocked(userId, intents, infos, resolvedTypes, token, options, VBinder.getCallingUid(), VBinder.getCallingPid());
        }
    }


    @Override
    public int getSystemPid() {
        return Process.myPid();
    }

    @Override
    public int getSystemUid() {
        return Process.myUid();
    }

    @Override
    public void onActivityCreated(IBinder record, IBinder token, int taskId) {
        int pid = Binder.getCallingPid();
        ProcessRecord targetApp;
        synchronized (mProcessLock) {
            targetApp = findProcessLocked(pid);
        }
        if (targetApp != null) {
            mActivityStack.onActivityCreated(targetApp, token, taskId, (ActivityRecord) record);
        }
    }

    @Override
    public void onActivityResumed(int userId, IBinder token) {
        mActivityStack.onActivityResumed(userId, token);
    }

    @Override
    public boolean onActivityDestroyed(int userId, IBinder token) {
        ActivityRecord r = mActivityStack.onActivityDestroyed(userId, token);
        return r != null;
    }

    @Override
    public void onActivityFinish(int userId, IBinder token) {
        mActivityStack.onActivityFinish(userId, token);
    }

    @Override
    public AppTaskInfo getTaskInfo(int taskId) {
        return mActivityStack.getTaskInfo(taskId);
    }

    @Override
    public String getPackageForToken(int userId, IBinder token) {
        return mActivityStack.getPackageForToken(userId, token);
    }

    @Override
    public ComponentName getActivityClassForToken(int userId, IBinder token) {
        return mActivityStack.getActivityClassForToken(userId, token);
    }


    private void processDied(ProcessRecord record) {
        mServices.processDied(record);
        mActivityStack.processDied(record);
        reBindDialerService(record);
    }

    //xdja
    public void reBindDialerService(ProcessRecord record){
        if(record.processName.equals("com.xdja.dialer")){
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try{
                        Thread.sleep(200);
                        Intent intent = new Intent(VirtualCore.get().getContext(), PhoneCallService.class);
                        VirtualCore.get().getContext().startService(intent);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    @Override
    public void finishAllActivities(){
        mActivityStack.finishAllActivities();
    }

    //xdja
    public void finishAllActivity(ProcessRecord record) {
        mActivityStack.finishAllActivity(record);
    }

    //xdja
    public boolean isAppForeground(String packageName, int userId) throws RemoteException {
        synchronized (mPidsSelfLocked) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId == userId && r.info.packageName.equals(packageName)) {
                    if(r.client.isAppForeground()){
                        Log.e(TAG, " process is foreground " + r.processName);
                        return true;
                    }
                }
            }
            return false;
        }
    }
    //xdja
    public boolean isForeground()throws RemoteException{
        synchronized (mPidsSelfLocked) {
            int N = mPidsSelfLocked.size();
            boolean foreground = false;
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                foreground|=r.client.isAppForeground();
            }
            Log.e(TAG, " process is foreground " + foreground);
            return foreground;
        }
    }


    @Override
    public IBinder acquireProviderClient(int userId, ProviderInfo info) {
        String processName = info.processName;
        ProcessRecord r;
        synchronized (this) {
            r = startProcessIfNeedLocked(processName, userId, info.packageName, -1, VBinder.getCallingUid(), VActivityManager.PROCESS_TYPE_PROVIDER);
        }
        if (r != null) {
            try {
                return r.client.acquireProviderClient(info);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void addOrUpdateIntentSender(IntentSenderData sender, int userId) {
        if (sender == null || sender.token == null) {
            return;
        }

        synchronized (mIntentSenderMap) {
            IntentSenderData data = mIntentSenderMap.get(sender.token);
            if (data == null) {
                mIntentSenderMap.put(sender.token, sender);
            } else {
                data.replace(sender);
            }
        }
    }

    @Override
    public void removeIntentSender(IBinder token) {
        if (token != null) {
            synchronized (mIntentSenderMap) {
                mIntentSenderMap.remove(token);
            }
        }
    }

    @Override
    public IntentSenderData getIntentSender(IBinder token) {
        if (token != null) {
            synchronized (mIntentSenderMap) {
                return mIntentSenderMap.get(token);
            }
        }
        return null;
    }

    private void cleanAllIntentSender(String packageName, int userId) {
        synchronized (mIntentSenderMap) {
            for (Map.Entry<IBinder, IntentSenderData> e : mIntentSenderMap.entrySet()) {
                IBinder sender = e.getKey();
                IntentSenderData data = e.getValue();
                if ((userId < 0 || data.userId == userId) && TextUtils.equals(packageName, data.creator)) {
                    //该应用的全部IntentSender
                    PendingIntent pendingIntent = null;
                    if (PendingIntentO.ctor != null) {
                        pendingIntent = PendingIntentO.ctor.newInstance(sender, null);
                    } else if (PendingIntentJBMR2.ctor != null) {
                        pendingIntent = PendingIntentJBMR2.ctor.newInstance(sender);
                    }
                    if (pendingIntent != null) {
                        try {
                            pendingIntent.cancel();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        }
    }

    @Override
    public ComponentName getCallingActivity(int userId, IBinder token) {
        return mActivityStack.getCallingActivity(userId, token);
    }

    @Override
    public String getCallingPackage(int userId, IBinder token) {
        return mActivityStack.getCallingPackage(userId, token);
    }


    @Override
    public VParceledListSlice<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags, int userId) {
        List<ActivityManager.RunningServiceInfo> infos = mServices.getServices(userId);
        return new VParceledListSlice<>(infos);
    }

    @Override
    public void processRestarted(String packageName, String processName, int userId) {
        int callingVUid = VBinder.getCallingUid();
        int callingPid = VBinder.getCallingPid();
        synchronized (this) {
            ProcessRecord app;
            synchronized (mProcessLock) {
                app = findProcessLocked(callingPid);
            }
            if (app == null) {
                String stubProcessName = getProcessName(callingPid);
                if (stubProcessName == null) {
                    return;
                }
                int vpid = parseVPid(stubProcessName);
                if (vpid != -1) {
                    startProcessIfNeedLocked(processName, userId, packageName, vpid, callingVUid, VActivityManager.PROCESS_TYPE_OTHER);
                }
            }
        }
    }

    public static int parseVPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else if (stubProcessName.startsWith(StubManifest.PACKAGE_NAME_64BIT)) {
            prefix = StubManifest.PACKAGE_NAME_64BIT + ":p";
        } else if (stubProcessName.startsWith(StubManifest.PACKAGE_NAME)) {
            prefix = VirtualCore.get().getHostPkg() + ":p";
        } else {
            return -1;
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }


    private String getProcessName(int pid) {
        for (ActivityManager.RunningAppProcessInfo info : VirtualCore.get().getRunningAppProcessesEx()) {
            if (info.pid == pid) {
                return info.processName;
            }
        }
        return null;
    }


    private boolean attachClient(final ProcessRecord app, final IBinder clientBinder) {
        IVClient client = IVClient.Stub.asInterface(clientBinder);
        if (client == null) {
            app.kill();
            return false;
        }
        try {
            clientBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    clientBinder.unlinkToDeath(this, 0);
                    onProcessDied(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.client = client;
        notifyAppProcessStatus(app, 0, true);
        try {
            app.appThread = ApplicationThreadCompat.asInterface(client.getAppThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return true;
    }

    //xdja
    private void notifyAppProcessStatus(ProcessRecord app, int uid, boolean status){
        try{
            if(status == true) {
                controllerManager.get().getService().appProcessStart(app.info.packageName, app.processName, app.pid);
                {
                    if (!isAppRunning(app.info.packageName, uid, false)) {
                        controllerManager.get().getService().appStart(app.info.packageName);
                    }
                }
            }else {
                controllerManager.get().getService().appProcessStop(app.info.packageName, app.processName, app.pid);
                {
//                    controllerManager.get().getService().appStop(app.info.packageName);
                    if (!isAppRunning(app.info.packageName, uid, false)) {
                        controllerManager.get().getService().appStop(app.info.packageName);
                    }

                }
            }
        }catch (RemoteException e){
            e.printStackTrace();
        }
    }

    private void onProcessDied(ProcessRecord record) {
        synchronized (mProcessLock) {
            mProcessNames.remove(record.processName, record.vuid);
            //bug：ProcessRecord#equals 根据processName判断，这样会移除多开的相同进程名的对象
            mPidsSelfLocked.remove(record);
        }
        //xdja
        notifyAppProcessStatus(record, 0, false);
        processDied(record);
        ActivityCounterManager.get().cleanProcess(record.pid);
        //xdja
        VLog.d(TAG, "onProcessDied:" + record.info.packageName);
        // TwinBox 2.1.32：guest 进程全退 → 撤掉后台保活（不留常驻通知）
        boolean guestStillAlive;
        synchronized (mProcessLock) {
            guestStillAlive = mProcessNames.getMap().size() > 0 || mPidsSelfLocked.size() > 0;
        }
        if (!guestStillAlive) {
            dev.twinbox.app.KeepAliveService.stop(VirtualCore.get().getContext());
        }
        // TwinBox 2.1.42：落盘密封——本包最后一个进程退出后异步密封数据目录。
        // hasLiveProcess 在锁外调（内部自己拿 mProcessLock）；sealPackageAsync
        // 的工作线程会拿 per-(pkg,userId) 锁后重查存活表，复活即放弃。
        if (!hasLiveProcess(record.info.packageName, record.userId)) {
            com.lody.virtual.server.seal.SealedStorage.sealPackageAsync(
                    record.info.packageName, record.userId);
        }
        VServiceKeepAliveService.get().scheduleRunKeepAliveService(record.info.packageName, VUserHandle.myUserId());
        //应用死了后，取消全部PendingIntent
//        if(!isAppRunning(record.info.packageName, record.userId, false)){
//            cleanAllIntentSender(record.info.packageName, record.userId);
//        }
    }

    /**
     * TwinBox 2.1.42：包是否还有存活进程（跨该包所有进程名，按 userId 过滤）。
     * 供密封模块判定「该包已整体离场」。只读表，不死等。
     */
    public boolean hasLiveProcess(String packageName, int userId) {
        synchronized (mProcessLock) {
            for (ProcessRecord r : mPidsSelfLocked) {
                if (r.info != null && r.info.packageName != null
                        && r.info.packageName.equals(packageName)
                        && r.userId == userId) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getFreeStubCount() {
        return StubManifest.STUB_COUNT - mPidsSelfLocked.size();
    }

    @Override
    public int checkPermission(boolean is64bit, String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (Manifest.permission.ACCOUNT_MANAGER.equals(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if ("android.permission.INTERACT_ACROSS_USERS".equals(permission) || "android.permission.INTERACT_ACROSS_USERS_FULL".equals(permission)) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (uid == 0) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return VPackageManagerService.get().checkUidPermission(is64bit, permission, uid);
    }

    @Override
    public ClientConfig initProcess(String packageName, String processName, int userId, int type) {
        synchronized (this) {
            ProcessRecord r = startProcessIfNeedLocked(processName, userId, packageName, -1, VBinder.getCallingUid(), type);
            if (r != null) {
                return r.getClientConfig();
            }
            return null;
        }
    }

    @Override
    public void appDoneExecuting(String packageName, int userId) {
        int pid = VBinder.getCallingPid();
        ProcessRecord r = findProcessLocked(pid);
        if (r != null) {
            r.pkgList.add(packageName);
        }
    }


    ProcessRecord startProcessIfNeedLocked(String processName, int userId, String packageName, int vpid, int callingUid, @VActivityManager.ProcessStartType int type) {
        runProcessGC();
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        ApplicationInfo info = VPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (ps == null || info == null) {
            // TwinBox 诊断灯：这里原先静默 return null → 上层 START_INTENT_NOT_RESOLVED(-1)，
            // 用户看到的是「点了没反应」。ps!=null && info==null ⇒ userState.installed=false
            // （generateApplicationInfo 被 checkUseInstalledOrHidden 拦截）。
            dev.twinbox.app.TLog.e("V|VAMS", "startProcessIfNeedLocked NULL: pkg=" + packageName
                    + " userId=" + userId + " ps=" + (ps == null ? "null" : "ok")
                    + " info=" + (info == null ? "null(installed=false?)" : "ok"));
            return null;
        }
        if (!ps.isLaunched(userId)) {
            sendFirstLaunchBroadcast(ps, userId);
            ps.setLaunched(userId, true);
            VAppManagerService.get().savePersistenceData();
        }
        // TwinBox 2.1.42：落盘密封——本包首个进程 spawn 前同步解封数据目录。
        // 必须在 initProcess（真正拉起 guest）之前：SQLite/open 在 guest 侧
        // 一开进程就可能碰文件，文件没解封=空库假象+旧密文被后续 seal 覆盖
        // （broken 标记防的就是那个连环坑）。幂等：无 .tbs 即空跑。
        com.lody.virtual.server.seal.SealedStorage.unsealPackage(packageName, userId);
        int vuid = VUserHandle.getUid(userId, ps.appId);
        boolean is64bit = ps.isRunPluginProcess();
        ProcessRecord app = null;
        synchronized (mProcessLock) {
            if (vpid == -1) {
                app = mProcessNames.get(processName, vuid);
                if (app != null) {
                    if (app.initLock != null) {
                        // TwinBox 2.1.5：initLock 无限 block 曾让后续点按全部永久挂死。
                        // 超时后放弃旧记录（首次 init 可能还在 provider 上挂着），
                        // 走下方重建流程——至少下次点按是可重试的而不是石沉大海。
                        boolean inited = app.initLock.block(15_000);
                        if (!inited) {
                            VLog.e(TAG, "initProcess TIMEOUT for " + processName
                                    + " — drop stale record, retry will respawn guest");
                            dev.twinbox.app.TLog.e("V|VAMS", "initLock TIMEOUT: " + processName
                                    + " vuid=" + vuid + " (guest engine broken or provider stuck)");
                            mProcessNames.remove(app.processName, app.vuid);
                            mPidsSelfLocked.remove(app);
                            app = null;
                        }
                    }
                    if (app != null && app.client != null) {
                        return app;
                    }
                }
                VLog.w(TAG, "start new process : " + processName + " by " + VActivityManager.getTypeString(type));
                vpid = queryFreeStubProcess(is64bit);
            }
            if (vpid == -1) {
                VLog.e(TAG, "Unable to query free stub for : " + processName);
                return null;
            }
            if (app != null) {
                VLog.w(TAG, "remove invalid process record: " + app.processName);
                mProcessNames.remove(app.processName, app.vuid);
                mPidsSelfLocked.remove(app);
            }
            app = new ProcessRecord(info, processName, vuid, vpid, callingUid, is64bit);
            mProcessNames.put(app.processName, app.vuid, app);
            mPidsSelfLocked.add(app);
        }
        // TwinBox 死锁修复（v2.1.4）：initProcess 内部是对 guest 进程的同步 provider 调用
        // （ProviderCall.callSafely），绝不能在 mProcessLock 内执行——
        // 旧结构：服务端 binder 线程持 mProcessLock 等 guest provider 回复；
        // guest 启动路径回调服务端（isAppPid/getAppProcessName/getUidByPid 都抢同一把锁）
        // → 持锁跨 binder 双向等待 = 死锁。症状：主线程 binder 永不返回 → ANR，
        // guest 卡死在 onVirtualProcess 之后。记录已在上面临界区注册，
        // 并发调用会走 initLock.block() 等待，语义不变。
        if (!initProcess(app)) {
            //init process fail
            dev.twinbox.app.TLog.e("V|VAMS", "initProcess FAILED (provider call null/no reply) for "
                    + processName + " — removing record");
            synchronized (mProcessLock) {
                mProcessNames.remove(app.processName, app.vuid);
                mPidsSelfLocked.remove(app);
            }
            app = null;
        } else {
            dev.twinbox.app.TLog.i("V|VAMS", "initProcess OK: " + processName
                    + " pid=" + app.pid);
            // TwinBox 2.1.38：guest 进程创建即挂保活（不再只依赖 startActivitiesLocked）。
            // 06_10-19-53 真机日志：guest 进程 :p0 闲置存活、用户划掉宿主任务时，
            // KeepAliveService 从未运行过（挂载点只有 Activity 启动那一条）→
            // OPlus Athena o-stop(40) with swipe up 把 main/:x/:pN 整个 uid 团灭，
            // guest 陪葬。initProcess 是一切 guest 进程的必经之路（Activity/Service/
            // Provider 拉起、clone 分身后首次启动都走这里），在这里挂才全覆盖。
            // startForegroundService 的合法性是 uid 级判定：宿主界面可见或 :x 被
            // guest 的 provider 拉着时，:x 也能起 FGS；失败仅记日志、降级不崩。
            dev.twinbox.app.KeepAliveService.start(VirtualCore.get().getContext());
        }
        if(app != null){
            //不需要在mProcessLock里面处理
            //申请到权限后，继续操作
            requestPermissionIfNeed(app, 8*1000);
//            if(!mResult){
//                app.kill();//权限没全部申请完
//                return null;
//            }
        }
        return app;
    }


    private void runProcessGC() {
        if (VActivityManagerService.get().getFreeStubCount() < 3) {
            // run GC
            killAllApps();
        }
    }

    private void sendFirstLaunchBroadcast(PackageSetting ps, int userId) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FIRST_LAUNCH, Uri.fromParts("package", ps.packageName, null));
        intent.setPackage(ps.packageName);
        intent.putExtra(Intent.EXTRA_UID, VUserHandle.getUid(ps.appId, userId));
        intent.putExtra("android.intent.extra.user_handle", userId);
        sendBroadcastAsUser(intent, new VUserHandle(userId));
    }


    @Override
    public int getUidByPid(int pid) {
        if (pid == Process.myPid()) {
            return Constants.OUTSIDE_APP_UID;
        }
        boolean isClientPid = false;
        if (pid == 0) {
            pid = VBinder.getCallingPid();
            isClientPid = true;
        }
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                if (isClientPid) {
                    return r.callingVUid;
                } else {
                    return r.vuid;
                }
            }
        }
        if (pid == Process.myPid()) {
            return Constants.OUTSIDE_APP_UID;
        }
        return Constants.OUTSIDE_APP_UID;
    }

    private void startRequestPermissions(boolean is64bit, String[] permissions,
                                         final ConditionVariable permissionLock) {

        PermissionCompat.startRequestPermissions(VirtualCore.get().getContext(), is64bit, permissions, new PermissionCompat.CallBack() {
            @Override
            public boolean onResult(int requestCode, String[] permissions, int[] grantResults) {
                try {
                    mResult = PermissionCompat.isRequestGranted(grantResults);
                } finally {
                    permissionLock.open();
                }
                return mResult;
            }
        });
    }


    /**
     * 初始化进程的ClientConfig
     */
    private boolean initProcess(ProcessRecord app) {
        try {
            //仅仅只是传递ClientConfig，还不需要用到权限
            Bundle extras = new Bundle();
            extras.putParcelable("_VA_|_client_config_", app.getClientConfig());
            Bundle res = ProviderCall.callSafely(app.getProviderAuthority(), "_VA_|_init_process_", null, extras);
            if (res == null) {
                return false;
            }
            app.pid = res.getInt("_VA_|_pid_");
            IBinder clientBinder = BundleCompat.getBinder(res, "_VA_|_client_");
            return attachClient(app, clientBinder);
        } finally {
            app.initLock.open();
            app.initLock = null;
        }
    }

    private void requestPermissionIfNeed(ProcessRecord app, int timeout) {
        if (PermissionCompat.isCheckPermissionRequired(app.info.targetSdkVersion)) {
            String[] permissions = VPackageManagerService.get().getDangrousPermissions(app.info.packageName);
            if (!PermissionCompat.checkPermissions(permissions, app.is64bit)) {
                mResult = false;
                final ConditionVariable permissionLock = new ConditionVariable();
                startRequestPermissions(app.is64bit, permissions, permissionLock);
                permissionLock.block(timeout);
            }
        }
    }

    public int queryFreeStubProcess(boolean is64bit) {
        synchronized (mProcessLock) {
            for (int vpid = 0; vpid < StubManifest.STUB_COUNT; vpid++) {
                int N = mPidsSelfLocked.size();
                boolean using = false;
                while (N-- > 0) {
                    ProcessRecord r = mPidsSelfLocked.get(N);
                    if (r.vpid == vpid && r.is64bit == is64bit) {
                        using = true;
                        break;
                    }
                }
                if (using) {
                    continue;
                }
                return vpid;
            }
        }
        return -1;
    }

    @Override
    public boolean isAppProcess(String processName) {
        return parseVPid(processName) != -1;
    }

    @Override
    public boolean isAppPid(int pid) {
        synchronized (mProcessLock) {
            return findProcessLocked(pid) != null;
        }
    }

    @Override
    public String getAppProcessName(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.processName;
            }
        }
        return null;
    }

    @Override
    public List<String> getProcessPkgList(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return new ArrayList<>(r.pkgList);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void killAllApps() {
        synchronized (mProcessLock) {
            for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                ProcessRecord r = mPidsSelfLocked.get(i);
                /* xdja
                ArrayList<ServiceRecord> tmprecord = new ArrayList<ServiceRecord>();
                synchronized (mHistory) {
                    for (ServiceRecord sr : mHistory) {
                        if (sr.process == r) {
                            tmprecord.add(sr);
                        }
                    }
                }
                for (ServiceRecord tsr : tmprecord) {
                    Log.e("wxd", " killService " + tsr.serviceInfo.toString() + " in " + r.processName + ":" + r.pid);
                    stopServiceCommon(tsr, ComponentUtils.toComponentName(tsr.serviceInfo));
                }
                Log.e("wxd", " killAllApps " + r.processName + " pid : " + r.pid);
                r.client.clearSettingProvider();
                finishAllActivity(r);
                */
                r.kill();
                if(CANCEL_ALL_NOTIFICATION_BY_KILL_APP){
                    VNotificationManagerService.get().cancelAllNotification(r.getPackageName(), -1);
                }
            }
        }
    }

    @Override
    public void killAppByPkg(final String pkg, final int userId) {
        if(CANCEL_ALL_NOTIFICATION_BY_KILL_APP) {
            VNotificationManagerService.get().cancelAllNotification(pkg, userId);
        }
        synchronized (mProcessLock) {
            ArrayMap<String, SparseArray<ProcessRecord>> map = mProcessNames.getMap();
            int N = map.size();
            while (N-- > 0) {
                SparseArray<ProcessRecord> uids = map.valueAt(N);
                if (uids != null) {
                    for (int i = 0; i < uids.size(); i++) {
                        final ProcessRecord r = uids.valueAt(i);
                        if (userId != VUserHandle.USER_ALL) {
                            if (r.userId != userId) {
                                continue;
                            }
                        }
                        if (r.pkgList.contains(pkg) || r.info.packageName.equals(pkg)) {
                            //xdja
                            try {
                                Log.e("wxd", " killAppByPkg  " + r.processName + r.pkgList.toString());
                                //processDied处有做处理
                                //mServices.stopServiceByPkg(userId, pkg);
                                r.client.clearSettingProvider();
                                finishAllActivity(r);
                            }catch (Exception e){
                                e.printStackTrace();
                            }finally {
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(600);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                        r.kill();
                                        if (CANCEL_ALL_NOTIFICATION_BY_KILL_APP) {
                                            VNotificationManagerService.get().cancelAllNotification(pkg, userId);
                                        }
                                    }
                                }).start();
                            }
                        }
                    }
                }
            }
        }

    }

    @Override
    public boolean isAppRunning(String packageName, int userId, boolean foreground) {
        boolean running = false;
        synchronized (mProcessLock) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId != userId) {
                    continue;
                }
                if (!r.info.packageName.equals(packageName)) {
                    continue;
                }
                if (foreground) {
                    if (!r.info.processName.equals(packageName)) {
                        continue;
                    }
                }
                try {
                    running = r.client.isAppRunning();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if(foreground) {
                    //foreground=true是只找主进程，false是全部进程任意一个存在
                    break;
                }
            }
            return running;
        }
    }

    public int getRunningAppMemorySize(String packageName, int userId) throws RemoteException {
        synchronized (mPidsSelfLocked) {
            int size = 0;
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId == userId && r.info.packageName.equals(packageName)) {
                    int[] pids = new int[] {r.pid};
                    Debug.MemoryInfo[] memoryInfo = am.getProcessMemoryInfo(pids);
                    size = size + memoryInfo[0].dalvikPrivateDirty;
                }
            }
            Log.i("wxd", " getRunningAppMemorySize : " + size);/**/
            return size;
        }
    }

    public void closeAllLongSocket(String packageName, int userId) throws RemoteException {
        synchronized (mPidsSelfLocked) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId == userId && r.info.packageName.equals(packageName)) {
                    r.client.closeAllLongSocket();
                }
            }
        }/**/
    }

    @Override
    public void killApplicationProcess(final String processName, int uid) {
        synchronized (mProcessLock) {
            ProcessRecord r = mProcessNames.get(processName, uid);
            if (r != null) {
                if (r.is64bit) {
                    V64BitHelper.forceStop64(r.pid);
                } else {
                    r.kill();
                }
            }
        }
    }

    @Override
    public void dump() {

    }

    @Override
    public String getInitialPackage(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.info.packageName;
            }
            return null;
        }
    }


    /**
     * Should guard by {@link VActivityManagerService#mPidsSelfLocked}
     *
     * @param pid pid
     */
    public ProcessRecord findProcessLocked(int pid) {
        for (ProcessRecord r : mPidsSelfLocked) {
            if (r.pid == pid) {
                return r;
            }
        }
        return null;
    }

    /**
     * Should guard by {@link VActivityManagerService#mProcessNames}
     *
     * @param uid vuid
     */
    private ProcessRecord findProcessLocked(String processName, int uid) {
        return mProcessNames.get(processName, uid);
    }

    public ProcessRecord findProcess(String processName, int uid) {
        synchronized (mProcessLock) {
            return findProcessLocked(processName, uid);
        }
    }

    public int stopUser(int userHandle, IStopUserCallback.Stub stub) {
        synchronized (mProcessLock) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId == userHandle) {
                    r.kill();
                }
            }
        }
        try {
            stub.userStopped(userHandle);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void sendOrderedBroadcastAsUser(Intent target, VUserHandle user, String receiverPermission,
                                           BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
                                           String initialData, Bundle initialExtras) {
        int userId = user == null ? VUserHandle.USER_ALL : user.getIdentifier();
        Intent intent = ComponentUtils.redirectBroadcastIntent(target, userId, BroadcastIntentData.TYPE_FROM_SYSTEM);
        VirtualCore.get().getContext().sendOrderedBroadcast(intent, null/* permission */, resultReceiver, scheduler, initialCode, initialData,
                initialExtras);
    }

    public void sendBroadcastAsUser(Intent target, VUserHandle user) {
        sendBroadcastAsUser(target, user, null);
    }

    public boolean bindServiceAsUser(Intent service, ServiceConnection connection, int flags, VUserHandle user) {
        service = new Intent(service);
        if (user != null) {
            service.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        return VirtualCore.get().getContext().bindService(service, connection, flags);
    }

    public void sendBroadcastAsUser(Intent target, VUserHandle user, String permission) {
        int userId = user == null ? VUserHandle.USER_ALL : user.getIdentifier();
        Intent intent = ComponentUtils.redirectBroadcastIntent(target, userId, BroadcastIntentData.TYPE_FROM_SYSTEM);
        VirtualCore.get().getContext().sendBroadcast(intent);
    }

    public void sendBroadcastAsUserWithPackage(Intent target, VUserHandle user, String targetPackage) {
        int userId = user == null ? VUserHandle.USER_ALL : user.getIdentifier();
        Intent intent = ComponentUtils.redirectBroadcastIntent(target, userId, BroadcastIntentData.TYPE_FROM_SYSTEM);
        if(!TextUtils.isEmpty(targetPackage)){
            intent.putExtra("_VA_|_privilege_pkg_", targetPackage);
        }
        VirtualCore.get().getContext().sendBroadcast(intent);
    }

    @Override
    public void notifyBadgerChange(BadgerInfo info) {
        Intent intent = new Intent(Constants.ACTION_BADGER_CHANGE);
        intent.putExtra("userId", info.userId);
        intent.putExtra("packageName", info.packageName);
        intent.putExtra("badgerCount", info.badgerCount);
                sendBroadcastAsUser(intent, VUserHandle.ALL);
    }

    @Override
    public int getCallingUidByPid(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.getCallingVUid();
            }
        }
        return -1;
    }

    @Override
    public void setAppInactive(String packageName, boolean idle, int userId) {
        synchronized (sIdeMap) {
            sIdeMap.put(packageName + "@" + userId, idle);
        }
    }

    @Override
    public boolean isAppInactive(String packageName, int userId) {
        synchronized (sIdeMap) {
            Boolean idle = sIdeMap.get(packageName + "@" + userId);
            return idle != null && !idle;
        }
    }

    private final ActiveServices mServices = new ActiveServices(this);

    @Override
    public ComponentName startService(int userId, Intent service) {
        synchronized (mServices) {
            return mServices.startService(userId, service);
        }
    }

    @Override
    public void stopService(int userId, ServiceInfo serviceInfo) {
        synchronized (mServices) {
            int appId = VUserHandle.getAppId(serviceInfo.applicationInfo.uid);
            int uid = VUserHandle.getUid(userId, appId);
            ProcessRecord r = findProcess(serviceInfo.processName, uid);
            if (r != null) {
                try {
                    r.client.stopService(ComponentUtils.toComponentName(serviceInfo));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void unbindService(int userId, IBinder token) {
        synchronized (mServices) {
            mServices.unbindService(userId, token);
        }
    }

    @Override
    public Intent bindService(int userId, Intent intent, ServiceInfo serviceInfo, IBinder binder, int flags) {
        synchronized (mServices) {
            return mServices.bindService(userId, intent, serviceInfo, binder, flags);
        }
    }

    @Override
    public void onServiceStartCommand(int userId, int startId, ServiceInfo serviceInfo, Intent intent) {
        synchronized (mServices) {
            mServices.onStartCommand(userId, startId, serviceInfo, intent);
        }
    }

    @Override
    public int onServiceStop(int userId, ComponentName component, int targetStartId) {
        synchronized (mServices) {
            mServices.setServiceForeground(component, userId, 0, null, true);
            return mServices.stopService(userId, component, targetStartId);
        }
    }

    @Override
    public void onServiceDestroyed(int userId, ComponentName component) {
        synchronized (mServices) {
            mServices.onDestroy(userId, component);
        }
    }

    @Override
    public ServiceResult onServiceUnBind(int userId, ComponentName component) {
        synchronized (mServices) {
            return mServices.onUnbind(userId, component);
        }
    }

    @Override
    public void setServiceForeground(ComponentName component, int userId, int id, String tag, boolean cancel){
        synchronized (mServices) {
            mServices.setServiceForeground(component, userId, id, tag, cancel);
        }
    }

    @Override
    public void handleDownloadCompleteIntent(Intent intent) {
        intent.setPackage(null);
        intent.setComponent(null);
        sendBroadcastAsUser(intent, VUserHandle.ALL);
    }


    public void beforeProcessKilled(ProcessRecord processRecord) {
        // EMPTY
    }

    void scheduleStaticBroadcast(final BroadcastIntentData data, final int appId, final ActivityInfo info, final int flags,final BroadcastReceiver.PendingResult result) {
        if (!handleStaticBroadcast(data, appId, info, flags, result)) {
            result.finish();
        }
    }

    private boolean handleStaticBroadcast(BroadcastIntentData data, int appId, ActivityInfo info, final int flags, BroadcastReceiver.PendingResult result) {
        if (data.userId >= 0) {
            return handleStaticBroadcastAsUser(data, appId, data.userId, info, flags, result);
        } else {
            int[] users = VAppManagerService.get().getPackageInstalledUsers(info.packageName);
            if (users.length == 1) {
                return handleStaticBroadcastAsUser(data, appId, users[0], info, flags, result);
            }
            for (int userId : users) {
                handleStaticBroadcastAsUser(data, appId, userId, info, flags, result);
            }
            return true;
        }
    }

    private boolean handleStaticBroadcastAsUser(BroadcastIntentData data, int appId, int userId, ActivityInfo info, final int flags, BroadcastReceiver.PendingResult result) {
        int vuid = VUserHandle.getUid(userId, appId);
        boolean send = false;
        synchronized (this) {
            ProcessRecord r = findProcess(info.processName, vuid);
            if (r == null &&
                    ((flags & BroadcastIntentData.TYPE_FROM_INTENT_SENDER) != 0 //通知栏之类的触发，允许唤醒应用
                            || isStartProcessForBroadcast(info.packageName, userId, data.intent.getAction()))) {
                r = startProcessIfNeedLocked(info.processName, userId, info.packageName, -1, -1, VActivityManager.PROCESS_TYPE_RECEIVER);
            }
            if (r != null && r.appThread != null) {
                send = true;
                performScheduleReceiver(r.client, vuid, info, data.intent, new PendingResultData(result, data.intent));
            } else {
                VLog.w(BroadcastSystem.TAG, "handleStaticBroadcastAsUser %s not running, ignore %s", info.name, data.intent.getAction());
            }
        }
        return send;
    }

    private boolean isStartProcessForBroadcast(String packageName, int userId, String action) {
        //是否允许因静态广播而启动进程
        return VirtualCore.getConfig().isAllowStartByReceiver(packageName, userId, action);
    }

    private void performScheduleReceiver(IVClient client, int vuid, ActivityInfo info, Intent intent,
                                         PendingResultData result) {
        int userId = VUserHandle.getUserId(vuid);
        ComponentName componentName = ComponentUtils.toComponentName(info);
        BroadcastSystem.get().broadcastSent(vuid, info, result, intent);
        try {
            client.scheduleReceiver(info.processName, componentName, intent, result);
        } catch (Throwable e) {
            if (result != null) {
                BroadcastSystem.get().broadcastFinish(result, userId);
            }
        }
    }

    @Override
    public void broadcastFinish(PendingResultData res, int userId) {
        BroadcastSystem.get().broadcastFinish(res, userId);
    }

    @Override
    public List<AppRunningProcessInfo> getRunningAppProcesses(String packageName, int userId) {
        List<AppRunningProcessInfo> list = new ArrayList<>();
        synchronized (mProcessLock) {
            for (ProcessRecord r : mPidsSelfLocked) {
                if (r.info.packageName.equals(packageName) && r.userId == userId) {
                    list.add(toAppRunningProcess(r));
                }
            }
        }
        return list;
    }

    private AppRunningProcessInfo toAppRunningProcess(ProcessRecord r){
        AppRunningProcessInfo info = new AppRunningProcessInfo();
        info.pid = r.pid;
        info.vuid = r.vuid;
        info.vpid = r.vpid;
        info.packageName = r.info.packageName;
        info.processName = r.processName;
        if (r.pkgList != null && r.pkgList.size() > 0) {
            info.pkgList.addAll(r.pkgList);
        } else {
            info.pkgList.add(r.info.packageName);
        }
        return info;
    }

    public Intent getStartStubActivityIntentInner(Intent intent, boolean is64bit, int vpid, int userId, IBinder resultTo, ActivityInfo info) {
        synchronized (this) {
            ActivityRecord targetRecord = mActivityStack.newActivityRecord(intent, info, resultTo, userId);
            return mActivityStack.getStartStubActivityIntentInner(intent, is64bit, vpid, userId, targetRecord, info);
        }
    }

    @Override
    public boolean includeExcludeFromRecentsFlag(IBinder token) {
        return mActivityStack.includeExcludeFromRecentsFlag(token);
    }

    @Override
    public void onBackHome() {
        synchronized (this) {
            lastBackHomeTime = System.currentTimeMillis();
            Log.e("kk-test", "lastBackHomeTime="+lastBackHomeTime);
        }
    }

    @Override
    public long getLastBackHomeTime() {
        synchronized (this) {
            return lastBackHomeTime;
        }
    }
}
