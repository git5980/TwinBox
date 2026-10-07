package dev.twinbox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * TwinBox 2.1.32：guest 后台保活（低优先级前台服务）。
 *
 * 背景（06_10-09-29 真机日志）：OPlus 的后台策略在容器应用退到后台时直接
 * `Force stopping dev.twinbox.app: o-stop(40)`，把 main / :x / :p0 / :p1
 * 四个进程一起杀——guest 的音乐播放跟着陪葬，用户看到的现象就是
 * 「音乐播放没有声音」（其实音频链路是好的：MediaPlayer 的 track 已经
 * STATE_ACTIVE，进程一死才 pause）。
 *
 * 做法：有 guest 在跑时就挂一个 IMPORTANCE_MIN 的前台服务，把整个应用
 * （同 uid 的全部进程）的优先级抬起来，OEM 的后台清理一般就不再对它下手；
 * guest 全部退出时自动撤销，不长期占通知栏。
 *
 * 注意：这是「降低被杀概率」，不是「免死金牌」——用户从最近任务划掉
 * （force-stop）一样会全死，那时需要系统层面的白名单/锁定配合。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "twinbox_keepalive";
    private static final int NOTIFY_ID = 0x4B41; // "KA"

    /** TwinBox 2.1.38：幂等标志——service 未运行时才真正发起 startForegroundService，
     * 避免每次 guest Activity 启动都打一次 binder。进程死亡后随类加载重置。 */
    private static volatile boolean sRunning = false;

    /** 引擎进程（:x）或宿主 UI 调用：确保保活前台服务在跑。 */
    public static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        if (sRunning) {
            return;
        }
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            sRunning = true;
            TLog.i("KeepAlive", "start requested from "
                    + android.os.Process.myUid() + "/" + getProcessNameCompat());
        } catch (Throwable t) {
            // Android 12+ 后台进程起 FGS 会被拒（ ForegroundServiceStartNotAllowed ）
            TLog.e("KeepAlive", "start fail", t);
        }
    }

    /** TwinBox 2.1.38：读当前进程名（诊断用——日志里直接看挂载来源是 :x 还是 main）。 */
    private static String getProcessNameCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return android.app.Application.getProcessName();
            }
            return (String) Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName").invoke(null);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** guest 全部退出后调用：撤掉前台服务。 */
    public static void stop(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, buildNotification());
            }
            TLog.i("KeepAlive", "foreground keep-alive ON（guest 运行中，后台清理豁免）");
        } catch (Throwable t) {
            // 前台化失败（后台启动被拒等）不该让容器崩，退化成普通 service
            TLog.e("KeepAlive", "startForeground fail, degrade to background", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // TwinBox 2.1.38：STICKY + 重新前台化。被系统低内存回收后重启（此时无
        // intent），onCreate 已 startForeground；这里对 redelivery 也补一次幂等调用。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, buildNotification());
            }
        } catch (Throwable ignore) {
        }
        return START_STICKY;
    }

    /**
     * TwinBox 2.1.38：用户从最近任务划掉宿主时系统回调这里。
     * FGS 的语义是「不因划掉而亡」——OPlus Athena 的 o-stop(40) 若尊重 FGS，
     * 进程会活下来（本日志就是直接证据，下轮看 KeepAlive: task removed 后
     * 有没有 Force stopping）。绝不能在这里 stopSelf()。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        TLog.i("KeepAlive", "task removed by swipe, FGS survives (o-stop check)");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        TLog.i("KeepAlive", "keep-alive OFF（guest 已全部退出）");
        sRunning = false;
        super.onDestroy();
    }

    private void ensureChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台保活",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignore) {
        }
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("双生盒正在后台运行")
                .setContentText("保持容器内应用（音乐/下载等）不被系统清理")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
