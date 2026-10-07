package com.lody.virtual.client.hook.base;

import android.text.TextUtils;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.annotations.LogInvocation;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Lody
 *         <p>
 *         HookHandler uses Java's {@link Proxy} to create a wrapper for existing services.
 *         <p>
 *         When any method is called on the wrapper, it checks if there is any {@link MethodProxy} registered
 *         and enabled for that method. If so, it calls the startUniformer instead of the wrapped implementation.
 *         <p>
 *         The whole thing is managed by a {@link MethodInvocationProxy} subclass
 */
@SuppressWarnings("unchecked")
public class MethodInvocationStub<T> {

    private static final String TAG = MethodInvocationStub.class.getSimpleName();

    private Map<String, MethodProxy> mInternalMethodProxies = new HashMap<>();
    private T mBaseInterface;
    private T mProxyInterface;
    private MethodProxy mDefaultProxy;
    private LogInvocation.Condition mInvocationLoggingCondition = LogInvocation.Condition.NEVER;


    public Map<String, MethodProxy> getAllHooks() {
        return mInternalMethodProxies;
    }


    public MethodInvocationStub(T baseInterface, Class<?>... proxyInterfaces) {
        this.mBaseInterface = baseInterface;
        if (baseInterface != null) {
            if (proxyInterfaces == null) {
                proxyInterfaces = MethodParameterUtils.getAllInterface(baseInterface.getClass());
            }
            mProxyInterface = (T) Proxy.newProxyInstance(baseInterface.getClass().getClassLoader(), proxyInterfaces, new HookInvocationHandler());
        }
    }

    public LogInvocation.Condition getInvocationLoggingCondition() {
        return mInvocationLoggingCondition;
    }

    public void setInvocationLoggingCondition(LogInvocation.Condition invocationLoggingCondition) {
        mInvocationLoggingCondition = invocationLoggingCondition;
    }

    public MethodInvocationStub(T baseInterface) {
        this(baseInterface, (Class[]) null);
    }

    /**
     * Copy all proxies from the input HookDelegate.
     *
     * @param from the HookDelegate we copy from.
     */
    public void copyMethodProxies(MethodInvocationStub from) {
        this.mInternalMethodProxies.putAll(from.getAllHooks());
    }

    /**
     * Add a method proxy.
     *
     * @param methodProxy proxy
     */
    public MethodProxy addMethodProxy(MethodProxy methodProxy) {
        if (methodProxy != null && !TextUtils.isEmpty(methodProxy.getMethodName())) {
            if (mInternalMethodProxies.containsKey(methodProxy.getMethodName())) {
                VLog.w(TAG, "The Hook(%s, %s) you added has been in existence.", methodProxy.getMethodName(),
                        methodProxy.getClass().getName());
                return methodProxy;
            }
            mInternalMethodProxies.put(methodProxy.getMethodName(), methodProxy);
        }
        return methodProxy;
    }

    /**
     * Remove a method proxy.
     *
     * @param hookName proxy
     * @return The proxy you removed
     */
    public MethodProxy removeMethodProxy(String hookName) {
        return mInternalMethodProxies.remove(hookName);
    }

    /**
     * Remove a method proxy.
     *
     * @param methodProxy target proxy
     */
    public void removeMethodProxy(MethodProxy methodProxy) {
        if (methodProxy != null) {
            removeMethodProxy(methodProxy.getMethodName());
        }
    }

    /**
     * Remove all method proxies.
     */
    public void removeAllMethodProxies() {
        mInternalMethodProxies.clear();
    }

    /**
     * Get the startUniformer by its name.
     *
     * @param name name of the Hook
     * @param <H>  Type of the Hook
     * @return target startUniformer
     */
    @SuppressWarnings("unchecked")
    public <H extends MethodProxy> H getMethodProxy(String name) {
        H proxy = (H) mInternalMethodProxies.get(name);
        if(proxy == null){
            return (H) mDefaultProxy;
        }
        return proxy;
    }

    public  void setDefaultMethodProxy(MethodProxy proxy){
        mDefaultProxy = proxy;
    }

    /**
     * @return Proxy interface
     */
    public T getProxyInterface() {
        return mProxyInterface;
    }

    /**
     * @return Origin Interface
     */
    public T getBaseInterface() {
        return mBaseInterface;
    }

    /**
     * @return count of the hooks
     */
    public int getMethodProxiesCount() {
        return mInternalMethodProxies.size();
    }

    /**
     * TwinBox 2.1.44：「文件不存在」是不是异常里的根因。
     *
     * <p>{@code Os.stat / Os.lstat / Os.access} 探测路径时，目标不存在就抛
     * {@code ErrnoException(ENOENT)}——这是 API 的正常返回路径，不是 hook 缺陷
     * （SharedPreferences 首读、profile/dex 产物探测全是它）。判它出来只为
     * 把日志降级：真 bug（ClassCastException 一类）照旧 E 级 + 全堆栈。
     * ITE 包装的往内剥一层，最多剥 4 层。
     */
    private static boolean isExpectedNoFile(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur instanceof android.system.ErrnoException) {
                return ((android.system.ErrnoException) cur).errno
                        == android.system.OsConstants.ENOENT;
            }
            cur = (cur instanceof InvocationTargetException) ? cur.getCause() : null;
        }
        return false;
    }

    private class HookInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            MethodProxy methodProxy = getMethodProxy(method.getName());
            boolean useProxy = VirtualCore.get().isStartup() && methodProxy != null && methodProxy.isEnable();
            boolean mightLog = (mInvocationLoggingCondition != LogInvocation.Condition.NEVER) ||
                    (methodProxy != null && methodProxy.getInvocationLoggingCondition() != LogInvocation.Condition.NEVER);

            String argStr = null;
            Object res = null;
            Throwable exception = null;
            if (mightLog) {
                // Arguments to string is done before the method is called because the method might actually change it
                try {
                    argStr = Arrays.toString(args);
                    argStr = argStr.substring(1, argStr.length() - 1);
                } catch (Throwable e) {
                    argStr = "" + e.getMessage();
                }
            }


            Object hookResult = null;
            boolean hooked = false;
            if (useProxy) {
                try {
                    if (methodProxy.beforeCall(mBaseInterface, method, args)) {
                        hookResult = methodProxy.call(mBaseInterface, method, args);
                        hooked = true;
                    }
                } catch (Throwable t) {
                    // TwinBox 2.1.17：hook 自身抛异常时不能让 guest 跟着死。
                    // 典型场景：Android 14+/16 上 IPackageManager 等方法的 flags 参数
                    // 由 int 被改成 long，老代码里的 (Integer) args[N] 抛
                    // ClassCastException: Long cannot be cast to Integer，
                    // 异常顺着 ActivityThread 冒到 guest 主线程就是一次闪退
                    // （见 crash-video-player-videoplayer-05_10-10-34-11_738.log）。
                    // 兜底策略：本次 hook 作废，透传原始系统实现，
                    // 代价只是少一层虚拟化过滤，而不是整个进程被杀。
                    //
                    // TwinBox 2.1.19：兜底日志补参数和完整堆栈。前面几轮只打
                    // 异常 message，导致 bindServiceInstance 的
                    // ClassCastException 没法定位到具体哪一行（看不出来是
                    // BindService 的 flagsInt 改造漏同步了，还是引擎
                    // 深处抛的）。args 也可能 toString 炸，单独兜住。
                    //
                    // TwinBox 2.1.44：ENOENT 不算故障，压成一行。
                    // Os.stat/Os.lstat 探测「文件在不在」时，不存在就是 ENOENT，
                    // 这是 API 的正常返回路径（SharedPreferences 首读、profile/
                    // dex 产物探测全是它）。以前这类也走 E 级 + 全堆栈，
                    // DeepSeek 一次启动刷 8 条、真问题被淹在里面。
                    hooked = false;
                    if (isExpectedNoFile(t)) {
                        String argDump;
                        try {
                            argDump = Arrays.toString(args);
                        } catch (Throwable ignore) {
                            argDump = "<args toString failed>";
                        }
                        VLog.i(TAG, "hook ENOENT (expected, file absent): "
                                + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                                + " args=[" + argDump + "]");
                    } else {
                        String argDump;
                        try {
                            argDump = Arrays.toString(args);
                        } catch (Throwable ignore) {
                            argDump = "<args toString failed>";
                        }
                        VLog.e(TAG, "Hook crash, fallback to origin method: "
                                + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                                + " args=[" + argDump + "] : " + t);
                        VLog.e(TAG, VLog.getStackTraceString(t));
                    }
                }
            }

            try {
                if (hooked) {
                    res = methodProxy.afterCall(mBaseInterface, method, args, hookResult);
                } else {
                    res = invokeOrigin(method, args);
                }
                return res;

            } catch (Throwable t) {
                exception = t;
                if (exception instanceof InvocationTargetException && ((InvocationTargetException) exception).getTargetException() != null) {
                    exception = ((InvocationTargetException) exception).getTargetException();
                }
                throw exception;

            } finally {
                if (mightLog) {
                    int logPriority = mInvocationLoggingCondition.getLogLevel(useProxy, exception != null);
                    if (methodProxy != null) {
                        logPriority = Math.max(logPriority, methodProxy.getInvocationLoggingCondition().getLogLevel(useProxy, exception != null));
                    }
                    if (logPriority >= 0) {
                        String retString;
                        if (exception != null) {
                            retString = exception.toString();
                        } else if (method.getReturnType().equals(void.class)) {
                            retString = "void";
                        } else {
                            retString = String.valueOf(res);
                        }

                        Log.println(logPriority, TAG, method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(" + argStr + ") => " + retString);
                    }
                }
            }
        }
    }

        /**
         * TwinBox 2.1.27：钩子自检。
         * 换 guest 应用之前先看这一行就知道容器钩子在这台机器上还剩多少是活的：
         *  DEAD 的方法名 = 该服务在新版 Android 上被改名/删除（例如 Android 16 已经没有
         *  bindIsolatedService），相关虚拟化逻辑整条失效；早先 bindIsolatedService /
         *  addToDisplayAsUser 就是这么静默失效的，直到被具体应用踩到才暴露。
         * 只在 guest 进程初始化时跑一次，纯反射、无副作用。
         */
        public void selfCheck() {
            try {
                Map<String, MethodProxy> hooks = getAllHooks();
                if (hooks == null || hooks.isEmpty() || mProxyInterface == null) {
                    return;
                }
                // 服务名：binder 代理类（如 IAccountManager$Stub$Proxy）时取它实现的
                // 接口名，否则（BlockGuardOs 这类具体实现类）直接用类名。
                String svc = "?";
                if (mBaseInterface != null) {
                    Class<?> bc = mBaseInterface.getClass();
                    if (!java.lang.reflect.Proxy.isProxyClass(bc)) {
                        svc = bc.getSimpleName();
                    } else {
                        for (Class<?> c : mProxyInterface.getClass().getInterfaces()) {
                            if (!c.getName().startsWith("android.os.")) {
                                svc = c.getSimpleName();
                                break;
                            }
                        }
                    }
                }
                Set<String> exist = new HashSet<String>();
                for (Method m : mProxyInterface.getClass().getMethods()) {
                    exist.add(m.getName());
                }
                List<String> dead = new ArrayList<String>();
                for (String name : hooks.keySet()) {
                    if (!exist.contains(name)) {
                        dead.add(name);
                    }
                }
                java.util.Collections.sort(dead);
                if (dead.isEmpty()) {
                    VLog.i(TAG, "Hook self-check [" + svc + "]: "
                            + hooks.size() + " registered, all alive");
                } else {
                    VLog.w(TAG, "Hook self-check [" + svc + "]: "
                            + hooks.size() + " registered, DEAD on this API " + dead);
                }
            } catch (Throwable ignore) {
            }
        }

        /**
         * TwinBox 2.1.24：透传原始系统调用前的统一「调用者身份」改写。
         * 见 {@link MethodParameterUtils#replaceCallerPkg}：IActivityManager /
         * IActivityTaskManager 里带 callingPackage 的方法，AMS 会校验
         * 「包名是否属于调用 uid」，guest 包名不属于宿主 uid，原样透传就是
         * SecurityException（Permission Denial: package=&lt;guest&gt; does not
         * belong to uid=&lt;host&gt;）。
         * 集中在这一处兜住所有「没有专门钩子接」的方法（startAssistantActivity、
         * startActivityFromGameSession、startActivityInPackage、peekService…），
         * 不再依赖每个业务钩子各自记得改；对任何 guest 应用一视同仁。
         */
        private Object invokeOrigin(Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceCallerPkg(method, args);
            return method.invoke(mBaseInterface, args);
        }

    private void dumpMethodProxies() {
        StringBuilder sb = new StringBuilder(50);
        sb.append("*********************");
        for (MethodProxy proxy : mInternalMethodProxies.values()) {
            sb.append(proxy.getMethodName()).append("\n");
        }
        sb.append("*********************");
        VLog.e(TAG, sb.toString());
    }

}
