package com.lody.virtual.helper.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.lody.virtual.client.core.VirtualCore;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TwinBox 2.1.16：AttributionSource 根部消毒（真身 uid 版）。
 *
 * 完整案情（2.1.13→2.1.15 三代真机日志实锤）：
 *   1. 容器 libcore hook 挂了 Os.getuid → NativeEngine.onGetUid：guest 运行期
 *      返回 vuid（如 Xplayer=10002，UidSystem 分配），而内核真身是 10041。
 *      android.os.Process.myUid() 内部就是 Os.getuid() —— 同被污染。
 *   2. AttributionSource.myAttributionSource() 静态缓存据此构建
 *      (10002, guest包名) —— 这就是历代日志里的毒源。
 *   3. 服务端校验 binder 事务的内核 uid（10041）≠ source uid（10002）→ 拒绝。
 *   4. Android 16 又重构了 AttributionSource 内部（无 mUid 字段、无
 *      (int,String,String) 构造器、无 sDefaultAttributionSource 静态）——
 *      反射治愈全灭，兜底置 null 反而 NPE 致死（writeToParcel 空指针）。
 *
 * v2.1.16 对策：
 *   - realUid()：绕开 hook 取内核真身（宿主 ApplicationInfo.uid → /proc/self/status
 *     → 兜底 myUid）。
 *   - hostAttribution()：宿主 context 的现成 AttributionSource —— 框架自建、
 *     (真身uid, 宿主包名) 天生双过服务端两道校验（enforceCallingUid +
 *     "package running in process"），且完全无视 OEM 内部结构。
 *   - sanitizeContext：ApplicationInfo.uid 对齐真身 + ContextImpl.mAttributionSource
 *     整体替换为宿主实例。
 *   - healByDiscovery：运行时枚举字段按名+类型启发式就地治愈（含嵌套 state 对象），
 *     并一次性 dump 字段表（下次日志可见 OEM 真实结构）。
 *   - 永不置 null：null = NPE 致死；保留原值最多被 SecurityException 拒（可恢复）。
 */
public final class AttributionSanitizer {

    private static final String TAG = "V|AS";
    private static volatile int sRealUid = -1;
    private static final List<String> sLayoutLogged = new CopyOnWriteArrayList<>();

    private AttributionSanitizer() {
    }

    /**
     * 内核真身 uid。Process.myUid()/Os.getuid() 在 guest 运行期被 libcore hook
     * 返回 vuid——而服务端 enforceCallingUid 对比的是 binder 事务的内核 uid，
     * 必须绕开 hook 取真值。
     */
    public static int realUid() {
        if (sRealUid != -1) {
            return sRealUid;
        }
        // 1. 宿主 ApplicationInfo.uid：进程启动即由系统写入，早于一切 guest hook
        try {
            ApplicationInfo host = VirtualCore.get().getContext().getApplicationInfo();
            if (host != null && host.uid > 0) {
                sRealUid = host.uid;
                return sRealUid;
            }
        } catch (Throwable ignore) {
        }
        // 2. /proc/self/status：内核真身（IO redirect native 库未加载，此读不可伪造）
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.substring(4).trim().split("\\s+");
                        int uid = Integer.parseInt(parts[0]);
                        if (uid > 0) {
                            sRealUid = uid;
                            return sRealUid;
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignore) {
        }
        // 3. 兜底（被 hook 则返回 vuid——有值总比没有好）
        return android.os.Process.myUid();
    }

    /**
     * 宿主 context 的现成 AttributionSource：(真身uid, 宿主包名)。
     * 框架在进程启动时构建（早于 guest 绑定），天生干净；直接整体替换，
     * 完全无视 OEM 对 AttributionSource 内部结构的魔改。
     */
    public static Object hostAttribution() {
        try {
            return VirtualCore.get().getContext().getAttributionSource();
        } catch (Throwable t) {
            VLog.w(TAG, "hostAttribution fail: " + t);
            return null;
        }
    }

    /**
     * 消毒一个刚创建的 guest Context：uid 对齐真身 + attribution 整体换宿主实例。
     * 幂等；任何一步失败只记日志，绝不置 null、绝不抛出。
     */
    public static void sanitizeContext(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        final int realUid = realUid();
        try {
            // 1) ApplicationInfo.uid 对齐真身（ContextImpl 构造身份的原料）
            ApplicationInfo ai = context.getApplicationInfo();
            if (ai != null && ai.uid != realUid) {
                try {
                    ai.uid = realUid;
                } catch (Throwable ignore) {
                }
            }
            Class<?> impl = context.getClass();
            try {
                Field uf = impl.getDeclaredField("mUid");
                uf.setAccessible(true);
                if (uf.getInt(context) != realUid) {
                    uf.setInt(context, realUid);
                }
            } catch (Throwable ignore) {
            }
            // 2) mAttributionSource 整体替换为宿主实例（引用替换，结构无关）
            try {
                Object hostAttr = hostAttribution();
                if (hostAttr != null) {
                    Field af = impl.getDeclaredField("mAttributionSource");
                    af.setAccessible(true);
                    if (af.get(context) != hostAttr) {
                        af.set(context, hostAttr);
                        VLog.i(TAG, "context attribution replaced with host's");
                    }
                }
            } catch (Throwable t) {
                VLog.w(TAG, "replace mAttributionSource fail: " + t);
            }
        } catch (Throwable t) {
            VLog.w(TAG, "sanitizeContext fail: " + t);
        }
    }

    /**
     * guest 的 ApplicationInfo（LoadedApk/AppBindData 持有）uid 对齐真身。
     * 后续 Activity/Service 的 base context 从这里派生身份。
     */
    /** 宿主包名（guest 进程里一切「调用者身份」都应报它）。 */
    public static String hostPackageName() {
        try {
            String pkg = VirtualCore.get().getHostPkg();
            return (pkg == null || pkg.isEmpty()) ? null : pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * TwinBox 2.1.33：把 ApplicationInfo.packageName 换成宿主包名（幂等、只动这一项）。
     *
     * 案情（06_10-12-44 真机日志）：
     *   E AppOps: Bad call made by uid 1041. Package "com.puretone.player" does not belong to uid 10041.
     *   E AppOps: java.lang.SecurityException: Specified package "com.puretone.player" under uid 10041 ...
     *   AudioPlaybackConfiguration ... state:started ... mutedState:opPlayAudio
     * audioserver 给播放中的 track 查 PLAY_AUDIO 这个 appop 时，用的是
     * AttributionSource 里的 package；而 AttributionSource.myAttributionSource()
     * 的兜底路径读 ActivityThread.currentPackageName() ←
     * mBoundApplication.appInfo.packageName —— guest 包名。uid 又已是真身宿主
     * (10041)，「包名不属于该 uid」→ AppOps 直接 SecurityException → AudioService
     * 把这条播放判 MUTED_BY_OP_PLAY_AUDIO → **前台播放也没有声音**。
     * 把 AppBindData 里那份 appInfo 的包名换成宿主，兜底路径就报 (10041, dev.twinbox.app)
     * —— 双过 AppOps。
     */
    public static void sanitizePackageName(ApplicationInfo ai) {
        if (ai == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            String hostPkg = hostPackageName();
            if (hostPkg == null || hostPkg.equals(ai.packageName)) {
                return;
            }
            String before = ai.packageName;
            ai.packageName = hostPkg;
            VLog.i(TAG, "appInfo packageName: " + before + " -> host(" + hostPkg
                    + ") (PLAY_AUDIO appop / AttributionSource 兜底路径)");
        } catch (Throwable t) {
            VLog.w(TAG, "sanitizePackageName fail: " + t);
        }
    }

    public static void sanitizeApplicationInfo(ApplicationInfo ai) {
        if (ai == null) {
            return;
        }
        final int realUid = realUid();
        if (ai.uid != realUid) {
            try {
                ai.uid = realUid;
            } catch (Throwable t) {
                VLog.w(TAG, "set ai.uid fail: " + t);
            }
        }
    }

    /**
     * 发现式就地治愈：枚举设备 AttributionSource 的真实字段，按
     * 名含 uid（int/long）+ 名含 package（String）启发式写入；找不到则深入
     * 嵌套 state 对象。首次 dump 全部字段（诊断：下次日志可见 OEM 结构）。
     * 永不抛出、永不置 null。
     */
    public static boolean healByDiscovery(Object attr, int uid, String pkg, String tag) {
        if (attr == null || Build.VERSION.SDK_INT < 31) {
            return false;
        }
        try {
            Class<?> cls = attr.getClass();
            if (!"android.content.AttributionSource".equals(cls.getName())) {
                return false;
            }
            Field[] fields = cls.getDeclaredFields();
            String key = cls.getName() + "#" + fields.length;
            if (sLayoutLogged.size() < 4 && !sLayoutLogged.contains(key)) {
                sLayoutLogged.add(key);
                StringBuilder sb = new StringBuilder("layout:");
                for (Field f : fields) {
                    sb.append(f.getName()).append(':')
                            .append(f.getType().getSimpleName()).append(' ');
                }
                VLog.w(TAG, "[" + tag + "] " + sb);
            }
            Field uidF = null, pkgF = null, stateF = null;
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String n = f.getName().toLowerCase(Locale.ROOT);
                Class<?> t = f.getType();
                if (uidF == null && n.contains("uid") && (t == int.class || t == long.class)) {
                    uidF = f;
                } else if (pkgF == null && n.contains("package") && t == String.class) {
                    pkgF = f;
                } else if (stateF == null && n.contains("state")
                        && !t.isPrimitive() && t != String.class) {
                    stateF = f;
                }
            }
            if (uidF != null && pkgF != null) {
                uidF.setAccessible(true);
                pkgF.setAccessible(true);
                Object oldUid = uidF.get(attr);
                String oldPkg = (String) pkgF.get(attr);
                setUid(uidF, attr, uid);
                pkgF.set(attr, pkg);
                VLog.i(TAG, "[" + tag + "] healed by discovery: "
                        + oldUid + "/" + oldPkg + " -> " + uid + "/" + pkg);
                return true;
            }
            if (stateF != null) {
                stateF.setAccessible(true);
                Object state = stateF.get(attr);
                if (state != null) {
                    Field su = null, sp = null;
                    for (Field f : state.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        Class<?> t = f.getType();
                        if (su == null && n.contains("uid")
                                && (t == int.class || t == long.class)) {
                            su = f;
                        } else if (sp == null && n.contains("package") && t == String.class) {
                            sp = f;
                        }
                    }
                    if (su != null && sp != null) {
                        su.setAccessible(true);
                        sp.setAccessible(true);
                        setUid(su, state, uid);
                        sp.set(state, pkg);
                        VLog.i(TAG, "[" + tag + "] healed via nested state -> "
                                + uid + "/" + pkg);
                        return true;
                    }
                }
            }
            VLog.w(TAG, "[" + tag + "] discovery heal not applicable (see layout above)");
            return false;
        } catch (Throwable t) {
            VLog.w(TAG, "[" + tag + "] discovery heal fail: " + t);
            return false;
        }
    }

    private static void setUid(Field f, Object target, int uid) throws Exception {
        if (f.getType() == long.class) {
            f.setLong(target, uid);
        } else {
            f.setInt(target, uid);
        }
    }

    /**
     * 读 attribution 的 (uid, pkg) 用于日志（公共 getter 反射，读不到返回 "?/?"）。
     */
    public static String describe(Object attr) {
        if (attr == null) {
            return "null";
        }
        try {
            Method g = attr.getClass().getMethod("getUid");
            int u = (Integer) g.invoke(attr);
            try {
                Method gp = attr.getClass().getMethod("getPackageName");
                return u + "/" + (String) gp.invoke(attr);
            } catch (Throwable t) {
                return u + "/?";
            }
        } catch (Throwable t) {
            return "?/?";
        }
    }

    /**
     * 判断 attribution 是否已是干净身份 (realUid, hostPkg)。
     */
    public static boolean isClean(Object attr, int uid, String pkg) {
        if (attr == null) {
            return false;
        }
        try {
            Method g = attr.getClass().getMethod("getUid");
            int u = (Integer) g.invoke(attr);
            if (u != uid) {
                return false;
            }
            try {
                Method gp = attr.getClass().getMethod("getPackageName");
                return pkg.equals((String) gp.invoke(attr));
            } catch (Throwable t) {
                return true; // uid 已对，pkg 读不到视为对（避免误报）
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
