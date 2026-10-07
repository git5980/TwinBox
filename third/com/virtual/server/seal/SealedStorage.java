package com.lody.virtual.server.seal;

import android.content.Context;
import android.os.SystemClock;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.am.VActivityManagerService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * TwinBox 2.1.42：容器数据「落盘密封」（at-rest encryption）。
 *
 * 背景（用户需求，2026-10-07）：
 *   物理机上用 root 文件管理器（MT 管理器等）可以直接看到容器内 guest 应用
 *   的明文数据（聊天记录库、配置、文件……）。要求：物理机上不能直接看到明文。
 *
 * 方案（分层、诚实边界）：
 *   - guest 进程存活期间明文必然可见（运行时内存/文件句柄），任何用户态方案
 *     都做不到运行时也密文（mmap 直读内核页缓存，用户态无从拦截）。
 *   - 本层保证「静止态」：guest 全部进程退出后，其私有数据目录
 *     （databases / shared_prefs / files 等，cache 除外）整体 AES-256-GCM
 *     加密成 *.tbs 密文文件并擦除明文；下次任一 guest 进程启动前同步解封。
 *   - 密钥在 Android Keystore（硬件安全区）内生成且不可导出——root 也拿不走
 *     密钥本体；能做的只是 guest 运行时从内存 dump（运行时暴露是另一课题）。
 *
 * 事件链（全部跑 :x 引擎进程）：
 *   spawn 前   startProcessIfNeedLocked → unsealPackage(pkg,userId)（同步，
 *              解封完才放行进程——SQLite 打开前文件必须已就位）
 *   死亡后     onProcessDied → 该 pkg 无存活进程 → sealPackageAsync（后台线程，
 *              拿 per-(pkg,user) 锁后重查存活表再动手）
 *   引擎启动   sweepAll：补扫 o-stop 团灭等没走正常死亡回调的残留；
 *              禁用标记存在时反向全量解封（逃生通道）
 *
 * 竞态闭合：unseal 持 pkg 锁 → 放行 spawn；seal 持同一把锁 → 先查存活表。
 * guest 进程的唯一注册入口是 startProcessIfNeedLocked（mPidsSelfLocked.add
 * 仅此一处），因此「密封中起进程」在锁语义上不可能发生。
 *
 * 崩溃一致性：seal 写序「先写 .tbs 再擦明文」，unseal 写序「先写明文再删
 * .tbs」。任一方向中断，下次 sweep/unseal 都能从混合态收敛（.tbs 与明文并存
 * 时以 .tbs 解出覆盖明文、删除 .tbs）。解封失败（密钥失效等）写 broken 标记
 * 并拒绝后续 seal——宁可见到旧密文，不可让空库覆盖真数据。
 *
 * 已知边界（诚实声明）：
 *   - Android Keystore 密钥被清除（恢复出厂/凭证重置）后旧密文不可恢复；
 *   - 擦除是 best-effort 覆写+删除（闪存磨损均衡不保证物理抹除）；
 *   - guest APK 本体（virtual/app/）不加密——程序文件不是数据；
 *   - >256MB 的单文件跳过（防内存/时延爆炸），TLog 留痕。
 */
public final class SealedStorage {

    private static final String TAG = "V|SEAL";
    /** 密文魔数（版本 1）。 */
    private static final byte[] MAGIC = {'T', 'B', 'S', '1'};
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final long MAX_FILE = 256L * 1024 * 1024;
    private static final String KEY_ALIAS = "twinbox_seal_v1";
    /** 存在于宿主 files 目录则禁用并全量解封（root 文件管理器可建）。 */
    private static final String DISABLE_MARKER = ".twinbox_seal_off";
    /** TwinBox 2.1.46：默认关闭期间的显式启用标记（宿主 files 目录）。 */
    private static final String ENABLE_MARKER = ".twinbox_seal_on";
    /** 出现在 guest 数据目录则该目录树退出密封（解封失败防覆盖）。 */
    private static final String BROKEN_MARKER = ".twinbox_seal_broken";
    private static final String SUFFIX = ".tbs";
    /** cache 类目录：可再生的不入库（提速且无隐私价值）。 */
    private static final List<String> SKIP_DIRS = new ArrayList<>();
    /** TwinBox 2.1.46：程序文件后缀——绝不能密封/擦除（活进程可能正 mmap 执行它）。 */
    private static final List<String> SKIP_FILE_SUFFIXES = new ArrayList<>();

    static {
        SKIP_DIRS.add("cache");
        SKIP_DIRS.add("code_cache");
        // TwinBox 2.1.46：程序文件目录，不是用户数据
        SKIP_DIRS.add("lib");
        SKIP_DIRS.add("oat");
        SKIP_DIRS.add("dalvik-cache");

        SKIP_FILE_SUFFIXES.add(".so");
        SKIP_FILE_SUFFIXES.add(".apk");
        SKIP_FILE_SUFFIXES.add(".dex");
        SKIP_FILE_SUFFIXES.add(".oat");
        SKIP_FILE_SUFFIXES.add(".vdex");
        SKIP_FILE_SUFFIXES.add(".art");
    }

    /** per-(pkg,userId) 互斥锁（unseal / seal / 存活重查全走这里）。 */
    private static final Map<String, Object> sLocks = new ConcurrentHashMap<>();

    private static Object lockFor(String pkg, int userId) {
        final String key = userId + "/" + pkg;
        Object lock = sLocks.get(key);
        if (lock == null) {
            synchronized (sLocks) {
                lock = sLocks.get(key);
                if (lock == null) {
                    lock = new Object();
                    sLocks.put(key, lock);
                }
            }
        }
        return lock;
    }

    private SealedStorage() {
    }

    // ---------------------------------------------------------------- API

    /**
     * 禁用开关。
     *
     * TwinBox 2.1.46：**默认关闭**（老开关 `.twinbox_seal_off` 仍可强制关闭；
     * 要试用得在宿主 files 下建 `.twinbox_seal_on`）。
     *
     * 为什么默认关：2.1.42-2.1.45 的遍历会跟着 guest 的 `lib` 软链走进原生库目录。
     * `VClient` 在每次 bindApplication 里把
     * `data/user/<id>/<pkg>/lib` 做成指向 `data/app/<pkg>/lib` 的软链
     * （FileUtils.createSymlink：目录走 ln -s），而 sealTree 只判 isDirectory()
     * 不看软链、SKIP_DIRS 里也没有 lib —— 于是把 guest 的 .so 当成用户数据
     * 「加密 → 写 0 覆写 → 删明文」。wipe() 是从偏移 0 开始写零的：活进程里已经
     * mmap 的 .so 一旦被这样覆写，执行到那页就是 SIGILL ILL_ILLOPC
     * （tombstone 里该 .so 还会显示 (deleted)）。
     * 实测：com.deepseek.chat 进主界面 1.4s 后原生崩溃
     * （crash-com-deepseek-chat-07_10-13-06-47_524.log，libmmkv.so+0x19bb4）。
     * 数据本身不会丢（.tbs 在擦除前就写好了），但运行中的程序文件绝不能被擦。
     * 遍历改成「不跟软链 + 不碰程序文件 + 按包表核对身份 + 擦前重查存活」之前，
     * 功能默认关闭。
     */
    public static boolean isDisabled() {
        try {
            Context ctx = VirtualCore.get().getContext();
            if (ctx == null) {
                return true;
            }
            File files = ctx.getFilesDir();
            if (new File(files, DISABLE_MARKER).exists()) {
                return true;
            }
            return !new File(files, ENABLE_MARKER).exists();
        } catch (Throwable t) {
            // 判定不了按禁用处理——保守，不动用户数据
            return true;
        }
    }

    /**
     * 解封（同步，spawn 前调用）。幂等：无 .tbs 即空跑。
     * TwinBox 2.1.46：**不受禁用开关影响**——禁用只表示「不再密封」，已经密封的
     * 数据（包括被 2.1.42-2.1.45 误封的 .so）必须照常解封，否则 guest 起来就是缺文件。
     */
    public static void unsealPackage(String pkg, int userId) {
        synchronized (lockFor(pkg, userId)) {
            long t0 = SystemClock.uptimeMillis();
            int[] count = new int[1];
            boolean[] broken = new boolean[1];
            for (File dir : dataDirsOf(pkg, userId)) {
                unsealTree(dir, count, broken);
            }
            if (count[0] > 0) {
                dev.twinbox.app.TLog.i(TAG, "unseal " + pkg + "/" + userId
                        + ": " + count[0] + " files, "
                        + (SystemClock.uptimeMillis() - t0) + "ms"
                        + (broken[0] ? " [HAS BROKEN]" : ""));
            }
        }
    }

    /** 密封（后台线程调用）。锁内重查存活表——guest 可能已复活。 */
    public static void sealPackageAsync(final String pkg, final int userId) {
        if (isDisabled()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lockFor(pkg, userId)) {
                        if (VActivityManagerService.get()
                                .hasLiveProcess(pkg, userId)) {
                            return; // 复活了，收工
                        }
                        long t0 = SystemClock.uptimeMillis();
                        int[] count = new int[1];
                        for (File dir : dataDirsOf(pkg, userId)) {
                            sealTree(dir, count, pkg, userId);
                        }
                        if (count[0] > 0) {
                            dev.twinbox.app.TLog.i(TAG, "seal " + pkg + "/" + userId
                                    + ": " + count[0] + " files, "
                                    + (SystemClock.uptimeMillis() - t0) + "ms");
                        }
                    }
                } catch (Throwable e) {
                    dev.twinbox.app.TLog.e(TAG, "seal " + pkg + " fail", e);
                }
            }
        }, "TB-seal-" + pkg);
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 引擎启动扫尾（systemReady 后延迟调用）：
     * 禁用 → 全量解封；启用 → 补封所有空闲包（o-stop 残留恢复）。
     * 只允许 :x 服务进程跑（存活表只在服务端是真相）。
     */
    public static void sweepAll() {
        if (!VirtualCore.get().isServerProcess()) {
            return;
        }
        try {
            if (isDisabled()) {
                int[] count = new int[1];
                boolean[] broken = new boolean[1];
                for (File userDir : allUserDirs()) {
                    unsealTree(userDir, count, broken);
                }
                if (count[0] > 0) {
                    dev.twinbox.app.TLog.i(TAG, "sweep(unseal-all, disabled): "
                            + count[0] + " files");
                }
                return;
            }
            for (File userDir : allUserDirs()) {
                File[] pkgs = userDir.listFiles();
                if (pkgs == null) {
                    continue;
                }
                for (File pkgDir : pkgs) {
                    if (!pkgDir.isDirectory()) {
                        continue;
                    }
                    final String pkg = pkgDir.getName();
                    final int userId = parseUserId(userDir);
                    if (userId < 0) {
                        continue;
                    }
                    // TwinBox 2.1.46：只补封真实存在的包。目录名可能只是历史残留或
                    // ensureCreated 的探针垃圾目录（老日志里 `sweep seal 0/0: 29 files`
                    // 就是身份错配的产物），拿它当包名去 seal 会绕过存活检查、
                    // 锁也锁在错的 key 上。
                    if (!isInstalledPackage(pkg)) {
                        continue;
                    }
                    synchronized (lockFor(pkg, userId)) {
                        if (VActivityManagerService.get()
                                .hasLiveProcess(pkg, userId)) {
                            continue;
                        }
                        int[] count = new int[1];
                        for (File dir : dataDirsOf(pkg, userId)) {
                            sealTree(dir, count, pkg, userId);
                        }
                        if (count[0] > 0) {
                            dev.twinbox.app.TLog.i(TAG, "sweep seal " + pkg
                                    + "/" + userId + ": " + count[0] + " files");
                        }
                    }
                }
            }
        } catch (Throwable e) {
            dev.twinbox.app.TLog.e(TAG, "sweep fail", e);
        }
    }

    // ------------------------------------------------------------ 路径

    /** 某包某用户的全部数据目录变体（32/64 × data/de）。 */
    static List<File> dataDirsOf(String pkg, int userId) {
        List<File> out = new ArrayList<File>(4);
        try {
            out.add(VEnvironment.getDataUserPackageDirectory(userId, pkg));
            out.add(VEnvironment.getDataUserPackageDirectory64(userId, pkg));
            out.add(VEnvironment.getDeDataUserPackageDirectory(userId, pkg));
            out.add(VEnvironment.getDeDataUserPackageDirectory64(userId, pkg));
        } catch (Throwable ignore) {
        }
        return out;
    }

    /**
     * 全部 user 级目录（.../user/&lt;id&gt;、.../user_de/&lt;id&gt; 及 64 位变体）。
     *
     * TwinBox 2.1.46 修：老实现拿 probe（`.../user/0/0`，包名占位 "0"）的**父目录**再
     * 枚举子目录 —— 父目录已经是 `.../user/0`，于是返回的是**包目录**而不是 user 目录。
     * 后果：sweepAll 把包名当 userId 解析（parseUserId 全 -1 → 整轮空转），或把探针
     * 垃圾目录当包名 seal（日志里 `sweep seal 0/0: 29 files` 就是这么来的：身份与
     * 实际被遍历的目录不一致，存活检查锁在错的 key 上）。改成从四个 user 根显式枚举。
     */
    static List<File> allUserDirs() {
        List<File> out = new ArrayList<File>();
        try {
            List<File> userRoots = new ArrayList<File>(4);
            userRoots.add(VEnvironment.getUserDataDirectory(0).getParentFile());
            userRoots.add(VEnvironment.getUserDataDirectory64(0).getParentFile());
            userRoots.add(VEnvironment.getUserDeDataDirectory(0).getParentFile());
            userRoots.add(VEnvironment.getUserDeDataDirectory64(0).getParentFile());
            for (File root : userRoots) {
                addChildren(out, root);
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /** TwinBox 2.1.46：这个包真的装过吗（服务端包表，纯内存查，不走 binder）。 */
    private static boolean isInstalledPackage(String pkg) {
        try {
            return com.lody.virtual.server.pm.PackageCacheManager.getSetting(pkg) != null;
        } catch (Throwable t) {
            return false; // 认不出来就不动它的数据（保守）
        }
    }

    private static void addChildren(List<File> out, File dir) {
        File[] subs = dir == null ? null : dir.listFiles();
        if (subs == null) {
            return;
        }
        for (File s : subs) {
            if (s.isDirectory()) {
                out.add(s);
            }
        }
    }

    /** user 目录名即 userId（user/<id>）。 */
    static int parseUserId(File userDir) {
        try {
            return Integer.parseInt(userDir.getName());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ 遍历

    private static boolean shouldSkipDir(File dir) {
        return SKIP_DIRS.contains(dir.getName());
    }

    /** TwinBox 2.1.46：软链一律不跟。判不出来按软链算（保守：不动它）。 */
    private static boolean isSymlinkSafe(File f) {
        try {
            return com.lody.virtual.helper.utils.FileUtils.isSymlink(f);
        } catch (Throwable t) {
            return true;
        }
    }

    /** TwinBox 2.1.46：程序文件（.so/.apk/.dex/…）不是用户数据，不许密封/擦除。 */
    private static boolean isProgramFile(String name) {
        String lower = name.toLowerCase();
        for (String suffix : SKIP_FILE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** seal/unseal 中断留下的半成品 tmp 文件名特征。 */
    private static boolean isStrayArtifact(String name) {
        return name.endsWith(SUFFIX + ".tmp") || name.endsWith(SUFFIX + ".out");
    }

    /**
     * 密封一棵树。
     *
     * TwinBox 2.1.46 三重护栏（缺一条就会重演 .so 被擦掉的崩溃）：
     *   1. **不跟软链**：`data/user/&lt;id&gt;/&lt;pkg&gt;/lib` 是指向 `data/app/&lt;pkg&gt;/lib`
     *      的软链（VClient 每次 bindApplication 都会建），跟着它就进了原生库目录；
     *   2. **不碰程序文件**：lib/oat/dalvik-cache 目录与 .so/.apk/.dex/.odex 后缀直接跳过；
     *   3. **每个文件擦前重查存活**：guest 复活立刻收工，绝不在活进程身上写零。
     */
    private static void sealTree(File dir, int[] count, String pkg, int userId) {
        if (dir == null || !dir.isDirectory() || shouldSkipDir(dir)) {
            return;
        }
        if (isSymlinkSafe(dir)) {
            dev.twinbox.app.TLog.w(TAG, "seal skip (symlink dir, 不进): " + dir);
            return;
        }
        if (new File(dir, BROKEN_MARKER).exists()) {
            dev.twinbox.app.TLog.w(TAG, "seal skip (broken marker): " + dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                sealTree(f, count, pkg, userId);
            } else if (f.isFile() && isStrayArtifact(f.getName())) {
                // seal 中断的半成品（.tbs.tmp / .tbs.out）：不可信的残渣，删
                f.delete();
            } else if (f.isFile() && f.getName().endsWith(SUFFIX)) {
                // 已是密文，跳过（崩溃残留的明文另一半会由 unseal 收敛）
            } else if (f.isFile()) {
                if (BROKEN_MARKER.equals(f.getName()) || DISABLE_MARKER.equals(f.getName())) {
                    continue;
                }
                if (isProgramFile(f.getName())) {
                    continue; // 2.1.46：程序文件不密封
                }
                if (isSymlinkSafe(f)) {
                    dev.twinbox.app.TLog.w(TAG, "seal skip (symlink file): " + f);
                    continue;
                }
                if (VActivityManagerService.get().hasLiveProcess(pkg, userId)) {
                    dev.twinbox.app.TLog.w(TAG, "seal abort: " + pkg + "/" + userId
                            + " 复活了（进程存活），停止密封");
                    return;
                }
                try {
                    if (sealFile(f)) {
                        count[0]++;
                    }
                } catch (Throwable t) {
                    dev.twinbox.app.TLog.e(TAG, "sealFile fail: " + f, t);
                }
            }
        }
    }

    /**
     * 解封一棵树。
     *
     * TwinBox 2.1.46：这里**故意跟软链、且不跳过程序文件**，与 sealTree 相反。
     * 原因：2.1.42-2.1.45 误封的 .so 密文就躺在
     * `data/app/&lt;pkg&gt;/lib/`（只能通过 `data/user/&lt;id&gt;/&lt;pkg&gt;/lib` 这条软链走到），
     * 解封是「把明文写回来 + 删密文」，不是破坏性操作；不跟软链的话那些库永远回不来。
     */
    private static void unsealTree(File dir, int[] count, boolean[] broken) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                unsealTree(f, count, broken);
            } else if (f.isFile() && isStrayArtifact(f.getName())) {
                // unseal 中断的半成品：不可信，删（真源 .tbs 还在）
                f.delete();
            } else if (f.isFile() && f.getName().endsWith(SUFFIX)) {
                try {
                    if (unsealFile(f)) {
                        count[0]++;
                    }
                } catch (Throwable t) {
                    broken[0] = true;
                    try {
                        new File(dir, BROKEN_MARKER).createNewFile();
                    } catch (IOException ignore) {
                    }
                    dev.twinbox.app.TLog.e(TAG, "unsealFile FAIL (key lost?): " + f, t);
                }
            }
        }
    }

    // ------------------------------------------------------------ 加密

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** 明文 → 同目录 <name>.tbs（MAGIC + IV + 密文+tag），成功后擦除明文。 */
    static boolean sealFile(File plain) throws Exception {
        if (plain.length() == 0 || plain.length() > MAX_FILE) {
            if (plain.length() > MAX_FILE) {
                dev.twinbox.app.TLog.w(TAG, "seal skip (>256MB): " + plain);
            }
            return false;
        }
        File sealed = new File(plain.getParentFile(), plain.getName() + SUFFIX);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LEN) {
            throw new IOException("bad iv len " + (iv == null ? -1 : iv.length));
        }
        File tmp = new File(plain.getParentFile(), plain.getName() + SUFFIX + ".tmp");
        DataOutputStream out = null;
        InputStream in = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(tmp), 1 << 16));
            out.write(MAGIC);
            out.write(iv);
            CipherOutputStream cos = new CipherOutputStream(out, cipher);
            in = new BufferedInputStream(new FileInputStream(plain), 1 << 16);
            copy(in, cos);
            cos.close(); // flush tag（必须先于 rename 关闭）
            in.close();
            if (!tmp.renameTo(sealed)) {
                tmp.delete();
                throw new IOException("rename fail: " + sealed);
            }
            wipe(plain);
            return true;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    /** <name>.tbs → 明文（覆盖同名残留），成功后删除 .tbs。 */
    static boolean unsealFile(File sealed) throws Exception {
        String name = sealed.getName();
        File plain = new File(sealed.getParentFile(),
                name.substring(0, name.length() - SUFFIX.length()));
        File tmp = new File(plain.getParentFile(), plain.getName() + ".tbs.out");
        DataInputStream in = null;
        OutputStream out = null;
        try {
            in = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(sealed), 1 << 16));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("bad magic: " + sealed);
            }
            byte[] iv = new byte[IV_LEN];
            in.readFully(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(TAG_BITS, iv));
            CipherInputStream cis = new CipherInputStream(in, cipher);
            out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            copy(cis, out);
            out.close(); // 触发 tag 校验，失败在这抛（进 finally 清理）
            cis.close();
            if (plain.exists() && !plain.delete()) {
                throw new IOException("cannot replace stale plain: " + plain);
            }
            if (!tmp.renameTo(plain)) {
                tmp.delete();
                throw new IOException("rename fail: " + plain);
            }
            if (!sealed.delete()) {
                sealed.deleteOnExit();
            }
            return true;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    /** best-effort 擦除：覆写零一次 + 删除（闪存磨损均衡下尽力而为）。 */
    private static void wipe(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            byte[] zeros = new byte[1 << 16];
            long len = raf.length();
            while (len > 0) {
                int n = (int) Math.min(zeros.length, len);
                raf.write(zeros, 0, n);
                len -= n;
            }
        } catch (Throwable ignore) {
            // 覆写失败也照样删——删除是硬保证
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignore) {
                }
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
