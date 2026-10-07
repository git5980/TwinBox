package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.content.ComponentName;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import dev.twinbox.app.TLog;

import mirror.android.view.IAutoFillManager;

/**
 * TwinBox 2.1.46：autofill 服务钩子重写——原来这套钩子结构上就会漏。
 *
 * 案情（07_10-13-50 真机 · DeepSeek 2.6.1 · SDK 36）：
 * 点输入框 → 键盘不弹 → 整个界面卡住。system_server 侧真相：
 * <pre>
 * W Binder: Caught a RuntimeException from the binder stub implementation.
 * W Binder: java.lang.IllegalArgumentException: com.deepseek.chat is not a valid package
 * W Binder:   at AutofillManagerService$AutoFillManagerServiceStub.startSession(...:1707)
 * W Binder: Caused by: PackageManager$NameNotFoundException: com.deepseek.chat
 * </pre>
 * guest 侧（主线程 tid==pid）：
 * <pre>
 * W AutofillManager: Exception getting result from SyncResultReceiver:
 *     TimeoutException: Not called in 5000ms
 * </pre>
 *
 * 因果链（对着 android-16.0.0_r1 源码逐行核对）：
 * <ol>
 *   <li>{@code AutofillManager.startSessionLocked()} 把
 *       {@code ComponentName clientActivity}（args[8]，包名是 guest）随
 *       {@code mService.startSession(...)} 一起 IPC 出去，然后用
 *       {@code receiver.getIntResult()} **同步等结果，超时 5 秒**
 *       （SYNC_CALLS_TIMEOUT_MS），跑在 guest 主线程；</li>
 *   <li>服务端 {@code AutofillManagerServiceStub.startSession} 第一件事就是
 *       {@code getPackageInfoAsUser(clientActivity.getPackageName())}——
 *       容器里根本没有 com.deepseek.chat 这个真包 →
 *       {@code IllegalArgumentException: ... is not a valid package}；</li>
 *   <li>整个 IAutoFillManager 是 <b>oneway</b> 接口，异常只停留在服务端，
 *       客户端的 IResultReceiver 永远收不到回调 → 主线程干等满 5 秒；
 *       IME show 请求在同一时刻疯狂重试（一条日志里 13 次
 *       onRequestShow/onCancelled），表现为「键盘调不出 + 界面卡住」。</li>
 * </ol>
 *
 * AIDL（android-16.0.0_r1）里带包名身份的方法一共这几个，**同一条校验**：
 * <pre>
 *   void addClient(client, in ComponentName componentName, ...)          ← 老代码没钩
 *   void startSession(..., in ComponentName componentName, ...)          ← 钩了但没生效
 *   void updateOrRestartSession(..., in ComponentName componentName, ...) ← 钩了
 *   void isServiceEnabled(int userId, String packageName, ...)           ← 钩了
 * </pre>
 *
 * 老代码的结构性问题：三个 addMethodProxy 全放在 {@code inject()} 的 try
 * **后面**，反射只要失败（字段名变了 / context 为 null）就 {@code return}，
 * 整个服务裸奔——钩子一个都不注册。2.1.46 把注册提到构造期（{@code onBindMethods}），
 * 反射只留作「换已缓存实例」的锦上添花，失败也影响不到钩子本身。
 */
public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";

    private static final String AUTO_FILL_NAME = "autofill";

    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        // 注册时机必须在构造期：inject() 里的反射失败不能连带把钩子吞掉
        addMethodProxy(new ReplacePkgAndComponentProxy("startSession"));
        addMethodProxy(new ReplacePkgAndComponentProxy("updateOrRestartSession"));
        // 2.1.46：addClient 也带 ComponentName（AIDL 参数 2），同一个校验，
        // 漏它等于整个 autofill 会话建不起来
        addMethodProxy(new ReplacePkgAndComponentProxy("addClient"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isServiceEnabled"));
        // 2.1.46：兜底——没单独列名的 autofill 方法（restoreSession、
        // setAugmentedAutofillWhitelist、以后新增的…），只要参数里出现 guest
        // 包名/ComponentName 也换掉。getMethodProxy 查不到名字时会落到
        // setDefaultMethodProxy 上（见 MethodInvocationStub.getMethodProxy）。
        setDefaultMethodProxy(new SanitizePackageArgs());
    }

    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        // 反射只用于把「已经缓存住的 AutofillManager 实例」也换成代理；
        // 失败只影响存量实例，sCache 已经保证之后新建的都带钩子。
        patchOne(VirtualCore.get().getContext());
        patchOne(VClient.get().getCurrentApplication());
    }

    private void patchOne(android.content.Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Object am = ctx.getSystemService(AUTO_FILL_NAME);
            if (am == null) {
                return;
            }
            Field f = findServiceField(am.getClass());
            if (f == null) {
                TLog.w("V|Autofill", "mService field not found on " + am.getClass());
                return;
            }
            f.set(am, getInvocationStub().getProxyInterface());
        } catch (Throwable t) {
            TLog.w("V|Autofill", "patch cached AutofillManager fail: " + t);
        }
    }

    private static Field findServiceField(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mService");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * 把参数里最后一个 ComponentName 换成「宿主包名 + 原 class」。
     * startSession / updateOrRestartSession / addClient 的形状都是
     * （... , ComponentName, ...），包名即校验对象。
     *
     * 顺手把 userId 归一：服务端还有一道
     * {@code Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()))}，
     * 分身（虚拟 userId≠0）场景不过这道同样抛异常、同样 5 秒同步超时。
     */
    static class ReplacePkgAndComponentProxy extends ReplaceLastPkgMethodProxy {

        ReplacePkgAndComponentProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            int index = ArrayUtils.indexOfLast(args, ComponentName.class);
            if (index != -1) {
                ComponentName orig = (ComponentName) args[index];
                String from = orig.getPackageName();
                args[index] = new ComponentName(getHostPkg(), orig.getClassName());
                TLog.i("V|Autofill", getMethodName() + ": component " + from
                        + " -> " + getHostPkg() + " (avoid 'not a valid package')");
            }
            normalizeUserId(args);
            return super.beforeCall(who, method, args);
        }
    }

    /**
     * 虚拟 userId → 真实 userId。虚拟用户 0 时通常是恒等变换（宿主就跑在 user 0），
     * 分身（虚拟 userId 非 0）才能对上服务端那道
     * {@code userId == getUserId(getCallingUid())} 的校验。
     */
    private static void normalizeUserId(Object[] args) {
        if (args == null) {
            return;
        }
        int virtual = MethodProxy.getAppUserId();
        int real = MethodProxy.getRealUserId();
        if (virtual == real) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer && (Integer) args[i] == virtual) {
                args[i] = real;
            }
        }
    }

    /**
     * 兜底代理：透传前把参数里所有「guest 包名」换成宿主包名——
     * String 参数逐个看，ComponentName 与 ComponentName 列表也照样处理。
     * 没有匹配上的包名就不动，行为与原来完全一致。
     */
    static class SanitizePackageArgs extends MethodProxy {

        @Override
        public String getMethodName() {
            return "*";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args == null) {
                return true;
            }
            String hostPkg = getHostPkg();
            String appPkg = getAppPkg();
            if (appPkg == null || hostPkg == null || appPkg.equals(hostPkg)) {
                return true;
            }
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof String && appPkg.equals(a)) {
                    args[i] = hostPkg;
                    TLog.i("V|Autofill", method.getName() + ": arg" + i + " pkg "
                            + appPkg + " -> " + hostPkg);
                } else if (a instanceof ComponentName
                        && appPkg.equals(((ComponentName) a).getPackageName())) {
                    ComponentName cn = (ComponentName) a;
                    args[i] = new ComponentName(hostPkg, cn.getClassName());
                    TLog.i("V|Autofill", method.getName() + ": arg" + i + " component "
                            + appPkg + " -> " + hostPkg);
                } else if (a instanceof List && !((List<?>) a).isEmpty()
                        && ((List<?>) a).get(0) instanceof ComponentName) {
                    rewriteComponentList((List<?>) a, appPkg, hostPkg);
                }
            }
            return true;
        }

        @SuppressWarnings("unchecked")
        private void rewriteComponentList(List<?> list, String appPkg, String hostPkg) {
            for (int j = 0; j < list.size(); j++) {
                ComponentName cn = (ComponentName) list.get(j);
                if (appPkg.equals(cn.getPackageName())) {
                    ((List<ComponentName>) list).set(j,
                            new ComponentName(hostPkg, cn.getClassName()));
                }
            }
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }
}
