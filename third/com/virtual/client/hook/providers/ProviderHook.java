package com.lody.virtual.client.hook.providers;

import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.lody.virtual.client.hook.base.MethodBox;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import mirror.android.content.IContentProvider;

/**
 * @author Lody
 */

public class ProviderHook implements InvocationHandler {

    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";

    public static final String QUERY_ARG_SQL_SELECTION_ARGS =
            "android:query-arg-sql-selection-args";
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";


    private static final Map<String, HookFetcher> PROVIDER_MAP = new HashMap<>();

    static {
        PROVIDER_MAP.put("settings", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new SettingsProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("downloads", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new DownloadProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("com.android.badge", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new BadgeProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("com.huawei.android.launcher.settings", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new BadgeProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("com.android.externalstorage.documents", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new DocumentHook(provider);
            }
        });
    }

    protected final Object mBase;

    public ProviderHook(Object base) {
        this.mBase = base;
    }

    private static HookFetcher fetchHook(String authority) {
        Log.i("wxd", " authority : " + authority);
        HookFetcher fetcher = PROVIDER_MAP.get(authority);
        if (fetcher == null) {
            fetcher = new HookFetcher() {
                @Override
                public ProviderHook fetch(boolean external, IInterface provider) {
                    if (external) {
                        return new ExternalProviderHook(provider);
                    }
                    return new InternalProviderHook(provider);
                }
            };
        }
        return fetcher;
    }

    private static IInterface createProxy(IInterface provider, ProviderHook hook) {
        if (provider == null || hook == null) {
            return null;
        }
        return (IInterface) Proxy.newProxyInstance(provider.getClass().getClassLoader(), new Class[]{
                IContentProvider.TYPE,
        }, hook);
    }

    public static IInterface createProxy(boolean external, String authority, IInterface provider) {
        if (provider instanceof Proxy && Proxy.getInvocationHandler(provider) instanceof ProviderHook) {
            return provider;
        }
        ProviderHook.HookFetcher fetcher = ProviderHook.fetchHook(authority);
        if (fetcher != null) {
            ProviderHook hook = fetcher.fetch(external, provider);
            IInterface proxyProvider = ProviderHook.createProxy(provider, hook);
            if (proxyProvider != null) {
                provider = proxyProvider;
            }
        }
        return provider;
    }

    public Bundle call(MethodBox methodBox, String method, String arg, Bundle extras) throws InvocationTargetException {
        return methodBox.call();
    }

    public Uri insert(MethodBox methodBox, Uri url, ContentValues initialValues) throws InvocationTargetException {

        return (Uri) methodBox.call();
    }

    public Cursor query(MethodBox methodBox, Uri url, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder, Bundle originQueryArgs) throws InvocationTargetException {
        return (Cursor) methodBox.call();
    }

    public String getType(MethodBox methodBox, Uri url) throws InvocationTargetException {
        return (String) methodBox.call();
    }

    public int bulkInsert(MethodBox methodBox, Uri url, ContentValues[] initialValues) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public int delete(MethodBox methodBox, Uri url, String selection, String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public int update(MethodBox methodBox, Uri url, ContentValues values, String selection,
                      String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public ParcelFileDescriptor openFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (ParcelFileDescriptor) methodBox.call();
    }

    public AssetFileDescriptor openAssetFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (AssetFileDescriptor) methodBox.call();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object... args) throws Throwable {
        // TwinBox 2.1.11：AttributionSource 重建必须在 invoke 直调——
        // 子类 ExternalProviderHook.processArgs 重写且未调 super，放基类 processArgs 里
        // 会被覆盖链跳过（实测：SettingsProviderHook → External 直接盖掉）。
        try {
            fixAttributionSource(args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            processArgs(method, args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        MethodBox methodBox = new MethodBox(method, mBase, args);
        int start = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ? 1 : 0;
        try {
            String name = method.getName();
            if ("call".equals(name)) {
                if (BuildCompat.isQ()) {
                    start = 2;
                }
                String methodName = (String) args[start];
                String arg = (String) args[start + 1];
                Bundle extras = (Bundle) args[start + 2];
                return call(methodBox, methodName, arg, extras);
            } else if ("insert".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues initialValues = (ContentValues) args[start + 1];
                return insert(methodBox, url, initialValues);
            } else if ("getType".equals(name)) {
                return getType(methodBox, (Uri) args[0]);
            } else if ("delete".equals(name)) {
                Uri url = (Uri) args[start];
                String selection = (String) args[start + 1];
                String[] selectionArgs = (String[]) args[start + 2];
                return delete(methodBox, url, selection, selectionArgs);
            } else if ("bulkInsert".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues[] initialValues = (ContentValues[]) args[start + 1];
                return bulkInsert(methodBox, url, initialValues);
            } else if ("update".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues values = (ContentValues) args[start + 1];
                String selection = (String) args[start + 2];
                String[] selectionArgs = (String[]) args[start + 3];
                return update(methodBox, url, values, selection, selectionArgs);
            } else if ("openFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openFile(methodBox, url, mode);
            } else if ("openAssetFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openAssetFile(methodBox, url, mode);
            } else if ("query".equals(name)) {
                Uri url = (Uri) args[start];
                String[] projection = (String[]) args[start + 1];
                String selection = null;
                String[] selectionArgs = null;
                String sortOrder = null;
                Bundle queryArgs = null;
                if (BuildCompat.isOreo()) {
                    queryArgs = (Bundle) args[start + 2];
                    if (queryArgs != null) {
                        selection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
                        selectionArgs = queryArgs.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);
                        sortOrder = queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER);
                    }
                } else {
                    selection = (String) args[start + 2];
                    selectionArgs = (String[]) args[start + 3];
                    sortOrder = (String) args[start + 4];
                }
                return query(methodBox, url, projection, selection, selectionArgs, sortOrder, queryArgs);
            }
            return methodBox.call();
        } catch (Throwable e) {
            VLog.w("ProviderHook", "call: %s (%s) with error", method.getName(), Arrays.toString(args));
            if (e instanceof InvocationTargetException) {
                throw e.getCause();
            }
            throw e;
        }
    }

    protected void processArgs(Method method, Object... args) {

    }

    /**
     * TwinBox 2.1.16：AttributionSource 换宿主实例（终极版）。
     * 2.1.15 真机日志破案：容器 libcore hook 挂了 Os.getuid →
     * android.os.Process.myUid() 在 guest 运行期返回 vuid（10002）而非
     * 内核真身（10041）——历代"治愈"目标就错了。且 Android 16 重构了
     * AttributionSource（无 mUid 字段/无 (int,String,String) 构造器），
     * 旧反射全灭，兜底 null 反而 NPE 致死（writeToParcel 空指针）。
     * 新策略：换宿主 context 的现成 attribution（框架自建，天生
     * (真身uid, 宿主包名)，双过两道服务端校验，OEM 结构无关）
     * → 发现式就地治愈 → 毒源静态按类型替换 → 绝不置 null。
     */
    private static void fixAttributionSource(Object[] args) {
        if (Build.VERSION.SDK_INT < 31 || args == null || args.length == 0) {
            return;
        }
        Object first = args[0];
        if (first == null) {
            return;
        }
        if (!"android.content.AttributionSource".equals(first.getClass().getName())) {
            if (sDiagCount < 5) {
                sDiagCount++;
                dev.twinbox.app.TLog.w("V|PH", "fixAttr SKIP: args[0] is "
                        + first.getClass().getName());
            }
            return;
        }
        final String before = com.lody.virtual.helper.utils.AttributionSanitizer
                .describe(first);
        try {
            // 2.1.16 关键：真身 uid。Process.myUid()/Os.getuid() 被 libcore hook
            // （guest 运行期返回 vuid=10002），而服务端 enforceCallingUid 校验的
            // 是 binder 事务的内核真身 uid（10041）——历代"治愈"全错在这里。
            final int realUid = com.lody.virtual.helper.utils.AttributionSanitizer
                    .realUid();
            final String hostPkg = VirtualCore.get().getHostPkg();

            if (com.lody.virtual.helper.utils.AttributionSanitizer
                    .isClean(first, realUid, hostPkg)) {
                return;
            }

            // 主策略：整体换成宿主现成 attribution —— 框架自建、
            // (真身uid, 宿主包名) 天生双过服务端两道校验，
            // 且完全无视 OEM 对 AttributionSource 内部结构的魔改。
            boolean healed = false;
            try {
                Object hostAttr =
                        com.lody.virtual.helper.utils.AttributionSanitizer
                                .hostAttribution();
                if (hostAttr != null && hostAttr != first) {
                    args[0] = hostAttr;
                    healed = true;
                }
            } catch (Throwable t) {
                dev.twinbox.app.TLog.w("V|PH", "fixAttr: host swap fail: " + t);
            }

            // 备用：发现式就地治愈（枚举字段，含嵌套 state；顺带 dump 字段表）
            if (!healed) {
                healed = com.lody.virtual.helper.utils.AttributionSanitizer
                        .healByDiscovery(first, realUid, hostPkg, "PH");
            }

            // 毒源净化（一次性）：按类型扫静态字段，替换为宿主实例
            healStaticCache(first.getClass());

            // 终极：保留原值。绝不置 null——Android 16 实锤 null 会让
            // ContentProviderProxy.call 里 writeToParcel 直接 NPE 致死；
            // 原值最多被 SecurityException 拒（调用方可 catch，可恢复）。
            if (sFixCount < 8) {
                sFixCount++;
                dev.twinbox.app.TLog.i("V|PH", "fixAttr: " + before + " -> "
                        + (healed
                                ? "host(" + realUid + "/" + hostPkg + ")"
                                : "KEPT-AS-IS(no strategy)"));
            }
        } catch (Throwable t) {
            // 外层异常也绝不动 args[0]
            dev.twinbox.app.TLog.w("V|PH", "fixAttr OUTER fail (args untouched): " + t);
        }
    }

    private static volatile boolean sStaticHealAttempted = false;

    /**
     * TwinBox 2.1.16：按类型扫描静态字段并替换为宿主 attribution。
     * Android 16 上 sDefaultAttributionSource 已改名（2.1.15 日志实锤），
     * 静态 final 写不进就跳过——ProviderHook 换参是主防线，这里只是纵深。
     */
    private static void healStaticCache(Class<?> cls) {
        if (sStaticHealAttempted) {
            return;
        }
        sStaticHealAttempted = true;
        try {
            Object hostAttr =
                    com.lody.virtual.helper.utils.AttributionSanitizer.hostAttribution();
            if (hostAttr == null) {
                return;
            }
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() != cls) {
                    continue;
                }
                f.setAccessible(true);
                Object cur = f.get(null);
                if (cur != null && cur != hostAttr) {
                    try {
                        f.set(null, hostAttr);
                        dev.twinbox.app.TLog.w("V|PH", "fixAttr: static field '"
                                + f.getName() + "' replaced with host attribution");
                        return;
                    } catch (Throwable setFail) {
                        dev.twinbox.app.TLog.w("V|PH", "fixAttr: static field '"
                                + f.getName() + "' set fail: " + setFail);
                    }
                }
            }
            dev.twinbox.app.TLog.i("V|PH", "fixAttr: no replaceable static field");
        } catch (Throwable t) {
            dev.twinbox.app.TLog.w("V|PH", "fixAttr: static heal fail: " + t);
        }
    }

    private static int sDiagCount = 0;
    private static int sFixCount = 0;

    public interface HookFetcher {
        ProviderHook fetch(boolean external, IInterface provider);
    }
}
