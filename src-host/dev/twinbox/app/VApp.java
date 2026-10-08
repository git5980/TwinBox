package dev.twinbox.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import com.lody.virtual.remote.InstallOptions;
import com.lody.virtual.client.core.SettingConfig;
import com.lody.virtual.client.core.VirtualCore;

/**
 * TwinBox V2 入口：容器引擎启动 + 容器内静默安装回调。
 */
public class VApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        TLog.init(base);
        installCrashRecorder();
        TLog.i("VApp", "attachBaseContext pkg=" + base.getPackageName());
        HiddenApiBypass.exemptAllOnce();
        try {
            VirtualCore.get().startup(base, new SettingConfig() {
                @Override
                public String getHostPackageName() {
                    return getPackageName();
                }

                @Override
                public String getPluginEnginePackageName() {
                    // 64 位引擎即本包：单包承载双架构
                    return getPackageName();
                }

                @Override
                public boolean isEnableIORedirect() {
                    return true;
                }
            });
        } catch (Throwable t) {
            // TwinBox 2.1.5：引擎启动失败必须写 TLog（原先只进 logcat，TLog 文件完全不可见，
            // 导致 guest 半残状态被「startup ok」假日志掩盖，排查一整天）。
            TLog.e("VApp", "VIRTUAL CORE STARTUP FAILED process="
                    + android.os.Process.myPid(), t);
            // guest 进程引擎残废 = 永久僵尸（provider call 会挂在 waitStartup），
            // 直接自杀最干净；主/服务进程则继续跑（UI 还能用）。
            try {
                if (VirtualCore.get().isVAppProcess()) {
                    TLog.e("VApp", "guest engine broken, killing myself to avoid zombie");
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            } catch (Throwable ignore) {
            }
        }
        VirtualCore core = VirtualCore.get();
        TLog.i("VApp", "startup ok, initializing... process="
                + android.os.Process.myPid());
        core.initialize(new VirtualCore.VirtualInitializer() {
            @Override
            public void onMainProcess() {
                TLog.i("VApp", "onMainProcess");
                killZombieStubProcesses();
                // TwinBox 2.1.64：清安装中转残留（进程被杀路径留下的
                // cache/incoming.apk——用户在容器 cache 里发现的 APK 残留）。
                InstallCenter.cleanupLeftovers(VApp.this);
            }

            @Override
            public void onServerProcess() {
                TLog.i("VApp", "onServerProcess");
                killZombieStubProcesses();
            }

            @Override
            public void onVirtualProcess() {
                TLog.i("VApp", "onVirtualProcess");
                // 容器内触发安装请求时静默落到容器（VMOS 同款体验）
                core.setAppRequestListener(new VirtualCore.AppRequestListener() {
                    @Override
                    public void onRequestInstall(String path) {
                        TLog.i("VApp", "onRequestInstall: " + path);
                        try {
                            VirtualCore.get().installPackageSync(path,
                                    InstallOptions.makeOptions(false,
                                            InstallOptions.UpdateStrategy.COMPARE_VERSION));
                        } catch (Throwable t) {
                            TLog.e("VApp", "silent install fail: " + path, t);
                        }
                    }

                    @Override
                    public void onRequestUninstall(String pkg) {
                    }
                });
            }
        });
        TLog.i("VApp", "attachBaseContext DONE (engine ready path) pid="
                + android.os.Process.myPid());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // TwinBox 探针：走到 onCreate 说明 provider 即将发布（installContentProviders 在
        // attachBaseContext 之后、onCreate 之前）——服务端 acquire 能否成功就看这一步。
        TLog.i("VApp", "onCreate pid=" + android.os.Process.myPid());
    }

    /**
     * TwinBox 2.1.7：全局崩溃捕获进 TLog。
     * guest 内 Xplayer 等应用的未捕获异常只进 logcat（TA 不导 logcat，TLog 文件看不到），
     * 死因成谜。这里抢在系统 handler 之前把全栈写进 TLog，再交还系统正常流程。
     */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    TLog.e("VApp", "UNCAUGHT EXCEPTION in thread [" + t.getName()
                            + "] pid=" + android.os.Process.myPid(), e);
                } catch (Throwable ignore) {
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    /**
     * TwinBox 2.1.5：清理跨会话僵尸 stub 进程。
     * 主/服务进程重启后，上次留下的 :pN guest 僵尸（引擎半残、provider 卡死）仍占着
     * 进程位，会把新会话的 provider call / 幽灵操作搞乱。同 uid 可直接杀。
     */
    private void killZombieStubProcesses() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            String prefix = getPackageName() + ":p";
            int myPid = android.os.Process.myPid();
            for (android.app.ActivityManager.RunningAppProcessInfo pi : am.getRunningAppProcesses()) {
                if (pi.processName != null && pi.processName.startsWith(prefix) && pi.pid != myPid) {
                    TLog.w("VApp", "killing zombie stub process: " + pi.processName
                            + " pid=" + pi.pid);
                    try {
                        android.os.Process.killProcess(pi.pid);
                    } catch (Throwable t) {
                        TLog.w("VApp", "kill zombie fail: " + pi.processName + " " + t);
                    }
                }
            }
        } catch (Throwable t) {
            TLog.w("VApp", "zombie scan fail: " + t);
        }
    }
}
