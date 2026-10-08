package dev.twinbox.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.remote.InstallResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * TwinBox 2.1.60：安装任务中心（应用级单例）.
 *
 * 为什么存在：2.1.59 只把任务挪进了静态线程池（退出页面任务不死），
 * 但任务的状态与进度仍挂在 Activity 实例上——退出克隆页进度就看不见了
 * （用户实测：返回主页再进，进度消失）。
 * 本类把「任务状态」也上移到应用级：任何页面任何时刻进来，都能看到
 * 全部进行中/最近完成任务的真实进度。
 *
 * 进度是真值不是动画：
 *  - 克隆：轮询引擎私有目录的目标 APK 文件增长（宿主与引擎同 uid，
 *    可直接 length()）——字节级真实进度（上限 88%，收尾阶段不定）；
 *  - APK 安装：宿主拷贝到 cache 的循环里直接报已写字节（0-50%），
 *    引擎安装段同样轮询目标文件（50-88%）。
 *
 * 执行模型：单线程池（与 2.1.59 相同——安装彼此串行防竞态），
 * UI 读取（列表刷新）不在此池。
 *
 * TwinBox 2.1.64：cache 中转文件生命周期管理——runApk 的 incoming.apk
 * 装完（成功/失败/异常/早退）一律删除；App 冷启动兜底清一次（进程被杀
 * 路径留下的残留在下次启动时兜住）。
 */
public final class InstallCenter {

    public static final int TYPE_CLONE = 1;
    public static final int TYPE_MULTI = 2;
    public static final int TYPE_APK = 3;

    public static final int STATE_QUEUED = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_FINALIZING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_FAILED = 4;

    /** 保留最近 N 条完成任务供回看 */
    private static final int HISTORY_LIMIT = 8;

    public static final class Task {
        public final int type;
        /** APK 安装在解析前未知，解析成功后补真值（volatile） */
        public volatile String pkgName;
        public volatile String label;
        /** STATE_* */
        public volatile int state = STATE_QUEUED;
        /** 0-100；-1 = 不确定（收尾/解析段） */
        public volatile int progress = -1;
        public volatile String error;
        public final long createdAt = System.currentTimeMillis();
        /** 轮询基准：目标 APK 总字节（克隆=源 APK；装 APK=cache 落盘字节） */
        volatile long totalBytes;
        /** 进入终态的时刻（hasRecentlyFinished 判定用） */
        volatile long finishedAt;

        Task(int type, String pkgName, String label) {
            this.type = type;
            this.pkgName = pkgName;
            this.label = label == null ? (pkgName == null ? "?" : pkgName) : label;
        }

        public boolean isTerminal() {
            return state == STATE_DONE || state == STATE_FAILED;
        }

        public boolean isActive() {
            return state == STATE_QUEUED || state == STATE_RUNNING || state == STATE_FINALIZING;
        }
    }

    public interface Listener {
        /** 保证在主线程回调 */
        void onTaskUpdate(Task t);
    }

    private static volatile InstallCenter sInstance;

    public static InstallCenter get() {
        if (sInstance == null) {
            synchronized (InstallCenter.class) {
                if (sInstance == null) {
                    sInstance = new InstallCenter();
                }
            }
        }
        return sInstance;
    }

    private final ExecutorService mPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "TB-install");
        }
    });
    private final List<Task> mTasks = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private InstallCenter() {
    }

    // ---------------------------------------------------------------- API

    /** 同包名已有排队/进行中的任务时拒绝（防重复提交） */
    public boolean isPendingFor(String pkgName) {
        if (pkgName == null) {
            return false;
        }
        synchronized (mTasks) {
            for (Task t : mTasks) {
                if (pkgName.equals(t.pkgName) && t.isActive()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 进行中任务数（按钮徽标） */
    public int activeCount() {
        int n = 0;
        synchronized (mTasks) {
            for (Task t : mTasks) {
                if (t.isActive()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 任务快照（新的在前）。返回的列表是拷贝，可直接在 UI 线程用。 */
    public List<Task> snapshot() {
        synchronized (mTasks) {
            return new ArrayList<>(mTasks);
        }
    }

    /** 任务行的状态描述（面板/按钮共用） */
    public String describeState(Task t) {
        switch (t.state) {
            case STATE_QUEUED:
                return "排队中";
            case STATE_RUNNING:
                if (t.progress >= 0) {
                    return t.progress + "%";
                }
                return "进行中…";
            case STATE_FINALIZING:
                return "安装中…";
            case STATE_DONE:
                return "完成";
            case STATE_FAILED:
                return "失败：" + (t.error == null ? "未知" : t.error);
        }
        return "";
    }

    /** 清掉终态任务（保留进行中的） */
    public void clearFinished() {
        synchronized (mTasks) {
            for (int i = 0; i < mTasks.size(); ) {
                if (mTasks.get(i).isTerminal()) {
                    mTasks.remove(i);
                } else {
                    i++;
                }
            }
        }
        fireUi();
    }

    /** 最近 3 秒内有任务进入终态（宿主 UI 据此刷新容器标记） */
    public boolean hasRecentlyFinished() {
        long now = System.currentTimeMillis();
        synchronized (mTasks) {
            for (Task task : mTasks) {
                if (task.isTerminal() && task.finishedAt > 0 && now - task.finishedAt < 3000) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addListener(Listener l) {
        if (l != null) {
            mListeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    /** 克隆主空间应用到容器 */
    public boolean submitClone(String pkgName, String label) {
        if (isPendingFor(pkgName)) {
            return false;
        }
        final Task t = new Task(TYPE_CLONE, pkgName, label);
        t.totalBytes = sourceApkSize(pkgName);
        addTask(t);
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                runClone(t);
            }
        });
        return true;
    }

    /** 已克隆应用再开一个分身 */
    public boolean submitMulti(String pkgName, String label) {
        if (isPendingFor(pkgName)) {
            return false;
        }
        final Task t = new Task(TYPE_MULTI, pkgName, label);
        addTask(t);
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                t.state = STATE_RUNNING;
                t.progress = -1;
                notifyTask(t);
                int uid = VBox.cloneToNewUser(pkgName);
                if (uid >= 0) {
                    t.label = label + " · #" + uid;
                    t.state = STATE_DONE;
                    t.progress = 100;
                } else {
                    t.state = STATE_FAILED;
                    t.error = "多开失败（详见日志）";
                }
                finishTask(t, uid >= 0 ? "分身已创建（#" + uid + "）" : "多开失败：" + label);
            }
        });
        return true;
    }

    /** 从 SAF Uri 安装 APK（拷贝+安装全程进度） */
    public boolean submitApk(final Context ctx, final Uri uri, final String displayName) {
        final Task t = new Task(TYPE_APK, null, displayName);
        addTask(t);
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                runApk(ctx, uri, t);
            }
        });
        return true;
    }

    // ------------------------------------------------------------ 内部实现

    private void runClone(Task t) {
        Thread poller = startPoller(t);
        t.state = STATE_RUNNING;
        notifyTask(t);
        String toast;
        try {
            InstallResult r = VBox.cloneHostApp(t.pkgName);
            if (r != null && r.isSuccess) {
                t.state = STATE_DONE;
                t.progress = 100;
                toast = "克隆成功：" + t.label;
            } else {
                t.state = STATE_FAILED;
                t.error = r == null ? "已在容器中或失败" : String.valueOf(r.error);
                toast = "克隆失败：" + t.label + "（" + t.error + "）";
            }
        } catch (Throwable ex) {
            TLog.e("InstallCenter", "clone exception", ex);
            t.state = STATE_FAILED;
            t.error = String.valueOf(ex.getMessage());
            toast = "克隆异常：" + t.label;
        }
        stopPoller(poller, t);
        finishTask(t, toast);
    }

    private void runApk(Context ctx, Uri uri, Task t) {
        // 1) SAF 流拷到 cache —— 字节进度 0-50%
        File tmp = new File(ctx.getCacheDir(), "incoming.apk");
        long size = querySize(ctx, uri);
        t.state = STATE_RUNNING;
        t.progress = 0;
        notifyTask(t);
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                t.state = STATE_FAILED;
                t.error = "打不开所选文件";
                deleteQuietly(tmp);
                finishTask(t, "安装失败：打不开所选文件");
                return;
            }
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long written = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (size > 0) {
                    int p = (int) (written * 50 / size);
                    if (p != t.progress) {
                        t.progress = p;
                        notifyTask(t);
                    }
                }
            }
            out.close();
            in.close();
        } catch (Throwable ex) {
            TLog.e("InstallCenter", "copy apk fail", ex);
            t.state = STATE_FAILED;
            t.error = String.valueOf(ex.getMessage());
            deleteQuietly(tmp);
            finishTask(t, "读取失败：" + t.error);
            return;
        }
        if (tmp.length() == 0) {
            t.state = STATE_FAILED;
            t.error = "文件为空（可能不是 APK）";
            deleteQuietly(tmp);
            finishTask(t, "安装失败：文件为空");
            return;
        }
        // 2) 解析包名/标签（供展示与引擎段轮询）
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
            if (pi != null && pi.packageName != null) {
                if (isPendingFor(pi.packageName)) {
                    t.state = STATE_FAILED;
                    t.error = "该应用已有进行中的任务";
                    deleteQuietly(tmp);
                    finishTask(t, "安装失败：已有进行中的任务");
                    return;
                }
                t.pkgName = pi.packageName;
                if (pi.applicationInfo != null) {
                    CharSequence lbl = pm.getApplicationLabel(pi.applicationInfo);
                    if (lbl != null) {
                        t.label = lbl.toString();
                    }
                }
            }
        } catch (Throwable ignore) {
            // 解析不出就跳过轮询，仅缺精确度
        }
        // 3) 引擎安装段 —— 轮询私有目录目标文件 50-88%
        t.totalBytes = tmp.length();
        Thread poller = t.pkgName != null ? startPoller(t) : null;
        if (poller == null) {
            t.state = STATE_FINALIZING;
            t.progress = -1;
            notifyTask(t);
        }
        String toast;
        try {
            InstallResult r = VBox.installApk(ctx, tmp.getAbsolutePath());
            if (r != null && r.isSuccess) {
                t.state = STATE_DONE;
                t.progress = 100;
                toast = "安装成功：" + (r.packageName == null ? t.label : r.packageName);
            } else {
                t.state = STATE_FAILED;
                t.error = r == null ? "未知错误" : String.valueOf(r.error);
                toast = "安装失败：" + t.error;
            }
        } catch (Throwable ex) {
            TLog.e("InstallCenter", "install exception", ex);
            t.state = STATE_FAILED;
            t.error = String.valueOf(ex.getMessage());
            toast = "安装异常：" + t.label;
        }
        stopPoller(poller, t);
        // TwinBox 2.1.64：装完（无论成败）清掉 cache 里的安装中转文件——
        // 老代码从头到尾没删过，用户在容器 cache 里发现了 APK 残留
        // （SAF 选多大它就留多大，几百 MB 级垃圾）。
        deleteQuietly(tmp);
        finishTask(t, toast);
    }

    /**
     * TwinBox 2.1.64：冷启动兜底清理——进程被杀/崩溃路径留下的中转文件
     * 这里兜住。App 启动时调一次即可（幂等）。
     */
    public static void cleanupLeftovers(Context ctx) {
        try {
            File tmp = new File(ctx.getCacheDir(), "incoming.apk");
            if (tmp.exists() && tmp.delete()) {
                TLog.i("InstallCenter", "cleaned leftover incoming.apk: " + tmp.length() + " bytes");
            }
        } catch (Throwable t) {
            TLog.w("InstallCenter", "cleanup fail: " + t);
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.exists() && !f.delete()) {
                TLog.w("InstallCenter", "temp apk not deleted: " + f);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 进度轮询线程：盯引擎私有目录里的目标 APK 增长（真实字节进度）。
     * 克隆场景 0-88%；APK 安装的引擎段 50-88%（进度基准由调用方预设）。
     */
    private Thread startPoller(final Task t) {
        if (t.pkgName == null || t.totalBytes <= 0) {
            return null;
        }
        final int base = t.progress < 0 ? 0 : t.progress;
        final int span = 88 - base;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                File target;
                try {
                    target = VEnvironment.getPackageResourcePathNext(t.pkgName);
                } catch (Throwable e) {
                    return;
                }
                while (!t.isTerminal()) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        long len = target.length();
                        if (len > 0 && t.totalBytes > 0) {
                            int p = base + (int) (len * span / t.totalBytes);
                            if (p > t.progress && p < 89) {
                                t.progress = p;
                                t.state = p >= 88 ? STATE_FINALIZING : STATE_RUNNING;
                                notifyTask(t);
                            }
                        }
                    } catch (Throwable ignore) {
                        // 路径暂不可见（安装未开始）——继续等
                    }
                }
            }
        }, "TB-poller");
        th.setDaemon(true);
        th.start();
        return th;
    }

    private void stopPoller(Thread poller, Task t) {
        if (poller != null) {
            poller.interrupt();
        }
        if (t.state == STATE_RUNNING) {
            t.state = STATE_FINALIZING;
        }
    }

    private void finishTask(final Task t, final String toast) {
        t.finishedAt = System.currentTimeMillis();
        notifyTask(t);
        // 全局完成通知（无论哪个页面在前台都感知得到——这是「进度不消失」的另一半）
        final Context app = VirtualCore.get().getContext();
        if (app != null && toast != null) {
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(app, toast, Toast.LENGTH_LONG).show();
                }
            });
        }
        pruneHistory();
    }

    private void pruneHistory() {
        synchronized (mTasks) {
            int done = 0;
            for (int i = 0; i < mTasks.size(); i++) {
                if (mTasks.get(i).isTerminal()) {
                    done++;
                }
            }
            if (done <= HISTORY_LIMIT) {
                return;
            }
            // 从头删最老的已完成任务（顺序提交时间递增）
            for (int i = 0; i < mTasks.size() && done > HISTORY_LIMIT; ) {
                if (mTasks.get(i).isTerminal()) {
                    mTasks.remove(i);
                    done--;
                } else {
                    i++;
                }
            }
        }
    }

    private void addTask(Task t) {
        synchronized (mTasks) {
            // 新任务放最前（列表最新的在最上）
            mTasks.add(0, t);
        }
        notifyTask(t);
    }

    private void notifyTask(final Task t) {
        if (mListeners.isEmpty()) {
            return;
        }
        mMain.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : mListeners) {
                    try {
                        l.onTaskUpdate(t);
                    } catch (Throwable ignore) {
                    }
                }
            }
        });
    }

    /** 列表整体变化（清历史等）——通知 UI 全刷 */
    private void fireUi() {
        notifyTaskAll();
    }

    private void notifyTaskAll() {
        if (mListeners.isEmpty()) {
            return;
        }
        mMain.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : mListeners) {
                    try {
                        l.onTaskUpdate(null);
                    } catch (Throwable ignore) {
                    }
                }
            }
        });
    }

    private long sourceApkSize(String pkgName) {
        try {
            ApplicationInfo ai = VirtualCore.get().getContext().getPackageManager()
                    .getApplicationInfo(pkgName, 0);
            if (ai != null && ai.sourceDir != null) {
                return new File(ai.sourceDir).length();
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private long querySize(Context ctx, Uri uri) {
        try {
            Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                        return c.getLong(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }
}
