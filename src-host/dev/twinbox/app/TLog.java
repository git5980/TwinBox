package dev.twinbox.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 双文件日志：logcat 镜像 + 文件落盘。
 *
 * TwinBox 2.1.25：落盘目录三级策略，前两级都在「公共 Download/TwinBox」，
 * 不依赖任何授权也能让用户在文件管理器里直接看到：
 *   1) DIRECT     已授权「所有文件访问」→ 直接 FileWriter 写公共目录（追加最省事）；
 *   2) MEDIASTORE 未授权 → MediaStore.Downloads 建立/追加同一路径的文件
 *                 （Android 10+ 通过 MediaStore 写自己插入的下载类文件免权限）；
 *   3) PRIVATE    前两级都失败 → 回退应用外部私有目录 files/logs，日志不能丢。
 * 文件按天滚动，保留最近 7 份；UI 状态栏用 {@link #dirDesc()} 显示实际落点。
 */
public final class TLog {

    private static final String TAG_PREFIX = "TwinBox/";
    private static final int KEEP_FILES = 7;

    /** 落盘模式。 */
    private static final int MODE_NONE = 0;
    private static final int MODE_DIRECT = 1;
    private static final int MODE_MEDIASTORE = 2;
    private static final int MODE_PRIVATE = 3;

    private static final String PUBLIC_SUBDIR = "TwinBox";

    private static File sDir;              // 当前落盘目录（私有/直达模式有值，MediaStore 模式为 null）
    private static int sMode = MODE_NONE;
    private static String sDay;            // 当前日志文件对应的日期
    private static File sFile;             // DIRECT/PRIVATE 模式的文件
    private static Uri sUri;               // MEDIASTORE 模式的文件 uri
    private static BufferedWriter sWriter;

    /**
     * TwinBox 2.1.67：异步落盘队列。
     * 原实现 write() 是 synchronized + 每条 flush——guest 高频 hook 日志
     * （11 行/秒量级，多在主线程打）每条都同步写 FUSE 上的 MediaStore
     * 文件，主线程被反复卡住（容器内应用卡顿的头号嫌疑）。
     * 改造：logcat 镜像保持同步（便宜），文件落盘进队列，后台线程
     * 批量写+flush。队列满丢弃（日志排障有 logcat 兜底，不反卡业务）。
     */
    private static final java.util.concurrent.LinkedBlockingQueue<String[]> sQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(2000);
    private static Thread sWriterThread;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static boolean sTriedInit = false;

    private TLog() {
    }

    private static Context sCtx;

    /** 在 Application.attachBaseContext 最先调用。 */
    public static synchronized void init(Context ctx) {
        if (sTriedInit) {
            return;
        }
        sTriedInit = true;
        sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            // 1) 已授权「所有文件访问」→ 公共 Download/TwinBox 直写
            File dir = null;
            try {
                if (Environment.isExternalStorageManager()) {
                    File dl = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (dl != null) {
                        dir = new File(dl, PUBLIC_SUBDIR);
                        if (!dir.exists() && !dir.mkdirs()) {
                            dir = null;
                        } else if (!canWrite(dir)) {
                            dir = null;
                        }
                    }
                }
            } catch (Throwable ignore) {
                dir = null;
            }
            if (dir != null) {
                sMode = MODE_DIRECT;
                sDir = dir;
            } else {
                // 2) 未授权也尽量落公共 Download（MediaStore 免权限通道）
                sMode = MODE_MEDIASTORE;
                sDir = null;
            }
            // TwinBox 2.1.67：rollIfNeeded 移入后台线程（主线程零文件 I/O）。
            // 首条 init 日志先进队列，由后台线程落盘。
            startWriterThread();
            i("TLog", "log dir = " + dirDesc()
                    + (sMode == MODE_MEDIASTORE ? " (公共Download·免权限通道)" : ""));
        } catch (Throwable t) {
            // 3) 兜底：私有外部目录，日志不能丢
            try {
                sMode = MODE_PRIVATE;
                File ext = ctx.getExternalFilesDir(null);
                sDir = (ext != null ? ext : ctx.getFilesDir());
                File logs = new File(sDir, "logs");
                if (!logs.exists()) {
                    logs.mkdirs();
                }
                sDir = logs;
                sDay = null;
                sUri = null;
                sFile = null;
                startWriterThread();
                e("TLog", "log dir = " + dirDesc() + " (私有目录·降级)", t);
            } catch (Throwable ignore) {
                android.util.Log.e(TAG_PREFIX + "TLog", "init fail", ignore);
            }
        }
    }

    private static boolean canWrite(File dir) {
        try {
            File probe = new File(dir, ".probe");
            if (probe.exists() || probe.createNewFile()) {
                probe.delete();
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** 供 UI 显示的日志落点。 */
    public static String dirDesc() {
        if (sMode == MODE_DIRECT || sMode == MODE_MEDIASTORE) {
            return "Download/" + PUBLIC_SUBDIR + "/twinbox-"
                    + (sDay != null ? sDay : DAY.format(new Date())) + ".log";
        }
        return sDir != null ? sDir.getAbsolutePath() : "(未初始化)";
    }

    /** 日志是否落在用户可见的公共 Download 目录。 */
    public static boolean isPublic() {
        return sMode == MODE_DIRECT || sMode == MODE_MEDIASTORE;
    }

    private static void rollIfNeeded() throws Exception {
        String day = DAY.format(new Date());
        if (day.equals(sDay) && sWriter != null) {
            return;
        }
        closeWriter();
        sDay = day;
        String name = "twinbox-" + day + ".log";
        if (sMode == MODE_MEDIASTORE) {
            if (sCtx == null) {
                throw new IllegalStateException("no ctx");
            }
            Uri uri = ensureMediaStoreFile(sCtx, name);
            if (uri == null) {
                throw new IllegalStateException("mediastore insert fail");
            }
            OutputStream os = openAppend(sCtx, uri);
            if (os == null) {
                throw new IllegalStateException("mediastore open fail");
            }
            sUri = uri;
            sFile = null;
            sWriter = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            trimOldMediaStore(sCtx);
            return;
        }
        sUri = null;
        sFile = new File(sDir, name);
        sWriter = new BufferedWriter(new FileWriter(sFile, true));
        trimOld();
    }

    private static void closeWriter() {
        if (sWriter != null) {
            try {
                sWriter.flush();
            } catch (Throwable ignore) {
            }
            try {
                sWriter.close();
            } catch (Throwable ignore) {
            }
            sWriter = null;
        }
    }

    /** 找当天文件，没有就按 RELATIVE_PATH 插入一个新的（Android 10+ 免权限）。 */
    private static Uri ensureMediaStoreFile(Context ctx, String name) {
        ContentResolver cr = ctx.getContentResolver();
        Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR;
        try {
            Cursor c = cr.query(coll,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                            + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{name, rel}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        long id = c.getLong(0);
                        if (id > 0) {
                            return ContentUris.withAppendedId(coll, id);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.RELATIVE_PATH, rel);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            return cr.insert(coll, v);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 以追加方式打开 uri：先试 "wa"，不支持再试 "rw" + 定位到文件尾。 */
    private static OutputStream openAppend(Context ctx, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "wa");
            if (pfd != null) {
                return new ParcelFdOutputStream(pfd);
            }
        } catch (Throwable ignore) {
        }
        try {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd != null) {
                FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                long len = fos.getChannel().size();
                if (len > 0) {
                    fos.getChannel().position(len);
                }
                return new ParcelFdOutputStream(pfd);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 包一层，关闭时把 ParcelFileDescriptor 一起放掉。 */
    private static final class ParcelFdOutputStream extends FileOutputStream {

        private final ParcelFileDescriptor mPfd;

        ParcelFdOutputStream(ParcelFileDescriptor pfd) {
            super(pfd.getFileDescriptor());
            mPfd = pfd;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                try {
                    mPfd.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void trimOld() {
        try {
            File[] all = sDir.listFiles();
            if (all == null || all.length <= KEEP_FILES) {
                return;
            }
            List<File> logs = new ArrayList<File>();
            for (File f : all) {
                if (f.getName().startsWith("twinbox-") && f.getName().endsWith(".log")) {
                    logs.add(f);
                }
            }
            if (logs.size() <= KEEP_FILES) {
                return;
            }
            // 文件名含日期，字典序即时间序
            logs.sort(new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (int i = 0; i < logs.size() - KEEP_FILES; i++) {
                logs.get(i).delete();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void trimOldMediaStore(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            List<String> names = new ArrayList<String>();
            Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String rel = Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR;
            Cursor c = cr.query(coll,
                    new String[]{MediaStore.Downloads.DISPLAY_NAME},
                    MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{rel}, null);
            if (c == null) {
                return;
            }
            try {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null && n.startsWith("twinbox-") && n.endsWith(".log")) {
                        names.add(n);
                    }
                }
            } finally {
                c.close();
            }
            if (names.size() <= KEEP_FILES) {
                return;
            }
            Collections.sort(names);
            for (int i = 0; i < names.size() - KEEP_FILES; i++) {
                cr.delete(coll, MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                                + MediaStore.Downloads.RELATIVE_PATH + "=?",
                        new String[]{names.get(i), rel});
            }
        } catch (Throwable ignore) {
        }
    }

    public static void d(String tag, String msg) {
        write("D", tag, msg, null);
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg, null);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg, t);
    }

    /** 异常全栈进文件（logcat 只留首行摘要）。 */
    public static String stackOf(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * TwinBox 2.1.67：非 synchronized、零文件 I/O 的调用路径。
     * - logcat 镜像同步（便宜，logcat 有自己的异步缓冲）；
     * - D 级不再落文件（guest 权限查询/hook 探测类高频噪音占一半量，
     *   排障需要时看 logcat）；
     * - 其余进内存队列，后台 TB-logwriter 线程批量落盘。
     * 崩溃时队列尾巴可能丢（毫秒级窗口）——logcat 镜像兜底。
     */
    private static void write(String level, String tag, String msg, Throwable t) {
        try {
            android.util.Log.println(
                    "E".equals(level) ? android.util.Log.ERROR
                            : "W".equals(level) ? android.util.Log.WARN
                            : "I".equals(level) ? android.util.Log.INFO : android.util.Log.DEBUG,
                    TAG_PREFIX + tag, msg + (t == null ? "" : " : " + t));
        } catch (Throwable ignore) {
        }
        if (sMode == MODE_NONE || "D".equals(level)) {
            return;
        }
        String line = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        try {
            // 队列满→丢弃本条（不反卡业务线程）
            sQueue.offer(new String[]{line, t == null ? null : stackOf(t)});
        } catch (Throwable ignore) {
        }
    }

    /** 后台落盘线程：批量 drain + flush（500ms 空转周期）。 */
    private static void startWriterThread() {
        if (sWriterThread != null) {
            return;
        }
        synchronized (TLog.class) {
            if (sWriterThread != null) {
                return;
            }
            sWriterThread = new Thread("TB-logwriter") {
                @Override
                public void run() {
                    while (true) {
                        try {
                            String[] item = sQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (item == null) {
                                // 空转周期也 flush 一次（roll 后可能有残余缓冲）
                                idleFlush();
                                continue;
                            }
                            rollIfNeeded();
                            writeOne(item);
                            // 批量 drain 队列残余（一次 flush 落一批）
                            while ((item = sQueue.poll()) != null) {
                                writeOne(item);
                            }
                            sWriter.flush();
                        } catch (InterruptedException ie) {
                            // 不退出：日志线程陪跑进程生命周期
                        } catch (Throwable t) {
                            // roll/写失败不能杀死日志线程——退避后继续
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignore) {
                            }
                        }
                    }
                }

                private void writeOne(String[] item) throws IOException {
                    sWriter.write(item[0]);
                    sWriter.write('\n');
                    if (item[1] != null) {
                        sWriter.write(item[1]);
                        sWriter.write('\n');
                    }
                }

                private void idleFlush() {
                    try {
                        if (sWriter != null) {
                            sWriter.flush();
                        }
                    } catch (Throwable ignore) {
                    }
                }
            };
            sWriterThread.setDaemon(true);
            sWriterThread.start();
        }
    }
}
