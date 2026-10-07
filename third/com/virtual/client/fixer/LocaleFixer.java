package com.lody.virtual.client.fixer;

import android.content.Context;
import android.os.Build;
import android.os.LocaleList;

import com.lody.virtual.client.core.VirtualCore;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * TwinBox 2.1.40：guest 的 LocaleManager.setApplicationLocales 钩空。
 * TwinBox 2.1.43：降级为**第二层防护**（见文末）。
 *
 * 案情（真机，Android 16 / OnePlus PKR110 / SDK 36）：
 *   FATAL EXCEPTION: Thread-6
 *   java.lang.IllegalArgumentException: Unknown package: com.deepseek.chat for user 0
 *     at android.app.ILocaleManager$Stub$Proxy.setApplicationLocales
 *     at android.app.LocaleManager.setApplicationLocales
 *   系统 LocaleManagerService.isPackageOwnedByCaller 查的是「真身 uid 10041
 *   是否拥有 com.deepseek.chat」——真身是宿主，当然不拥有 → 抛异常 → 未捕获 → 崩。
 *
 * 与 2.1.33 的 PLAY_AUDIO appop 同构（guest 包名 + 宿主 uid 打到真系统服务）：
 * 音频那次是静音，这次直接 FATAL。DeepSeek 在后台线程调 per-app locale
 * 设置（App 内语言切换/初始化），属于常规操作，躲不开，只能接住。
 *
 * 处理：反射拿到 LocaleManager 实例里的 ILocaleManager binder，替换为
 * 动态代理——setApplicationLocales（两个重载同名）直接吞掉返回（app 以为
 * 设置成功，语言跟随系统，不崩）；其余方法原样透传。
 * per-app locale 本就按真包名存在系统侧，容器里没有这个真包，无法真正
 * 落地——吞掉是唯一安全降级，不是缺陷。
 *
 * 挂载：ContextFixer.fixContext（Application/Activity/Service 的 context
 * 都过这里）。LocaleManager 由 SystemServiceRegistry 按 context 懒创建缓存，
 * 所以每个被 fix 的 context 各 patch 一次（Proxy.isProxyInstance 幂等判重）。
 * hidden API 已由 VApp 里 HiddenApiBypass 全量豁免，mService 私有字段可反射。
 *
 * TwinBox 2.1.43 起这里只是第二层：**binder 层钩子**
 * （{@link com.lody.virtual.client.hook.proxies.locale.LocaleManagerStub}）
 * 换掉 sCache 后，之后每个 context 新建的 LocaleManager 自带钩子；本类继续
 * 兜住「在 sCache 被换之前就已创建、且刚好走过 fixContext」的存量实例。
 * 2.1.43 起只拦外来包名（`!getHostPkg().equals(pkg)`），宿主自己的 locale
 * 调用在系统侧合法，必须原样放行。
 */
public final class LocaleFixer {

    private static final String TAG = "V|Locale";

    private LocaleFixer() {
    }

    public static void fix(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            // LocaleManager.setApplicationLocales 是 API 33+，旧系统无此路径
            return;
        }
        try {
            Object lm = context.getSystemService(android.app.LocaleManager.class);
            if (lm == null) {
                return;
            }
            // 按类型找 ILocaleManager 字段（字段名 mService 在 AOSP 里稳定，
            // 但按类型匹配对 ROM 改名免疫）
            Field target = null;
            for (Field f : lm.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t.isInterface() && t.getName().contains("ILocaleManager")) {
                    target = f;
                    break;
                }
            }
            if (target == null) {
                dev.twinbox.app.TLog.w(TAG, "ILocaleManager field not found on "
                        + lm.getClass() + " — skip (ROM changed?)");
                return;
            }
            target.setAccessible(true);
            final Object original = target.get(lm);
            if (original == null || Proxy.isProxyClass(original.getClass())) {
                // 已 patch 过（幂等）或异常状态
                return;
            }
            Object proxy = Proxy.newProxyInstance(
                    lm.getClass().getClassLoader(),
                    new Class<?>[]{target.getType()},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                            String name = m.getName();
                            // 宿主自己的调用放行：容器宿主对系统侧合法（ContextFixer
                            // 已把 mBasePackageName 换成宿主，无参 API 传的就是宿主）
                            boolean foreignPkg = a != null && a.length > 0 && a[0] instanceof String
                                    && !VirtualCore.get().getHostPkg().equals(a[0]);
                            if (foreignPkg) {
                                if ("setApplicationLocales".equals(name)) {
                                    // 吞掉：真系统不认 (宿主uid, guest包名)，
                                    // 放行必抛 IllegalArgumentException
                                    dev.twinbox.app.TLog.i(TAG,
                                            "setApplicationLocales swallowed (guest pkg, "
                                                    + "per-app locale not supported in box)");
                                    return null;
                                }
                                if ("getApplicationLocales".equals(name)) {
                                    // 读路径同服务同校验（读当前设置→比对→再 set 是
                                    // DeepSeek 的调用模式）。返回空 LocaleList =
                                    // 「无 per-app 设置」→ app 回退系统语言，
                                    // 容器内的正确语义。不能返回 null——manager 层直通，
                                    // null 会变成 app 侧 NPE。
                                    dev.twinbox.app.TLog.i(TAG,
                                            "getApplicationLocales -> empty (guest pkg)");
                                    return LocaleList.getEmptyLocaleList();
                                }
                                if ("setOverrideLocaleConfig".equals(name)) {
                                    dev.twinbox.app.TLog.i(TAG,
                                            "setOverrideLocaleConfig swallowed (guest pkg)");
                                    return null;
                                }
                                if ("getOverrideLocaleConfig".equals(name)) {
                                    // null = 未覆盖，与 LocaleManager 的 @Nullable 一致
                                    dev.twinbox.app.TLog.i(TAG,
                                            "getOverrideLocaleConfig -> null (guest pkg)");
                                    return null;
                                }
                            }
                            try {
                                return m.invoke(original, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                    });
            target.set(lm, proxy);
        } catch (Throwable t) {
            // 语言设置钩挂失败不该影响启动——最坏情况是回到崩溃前的行为，
            // 但那只在「ROM 改了字段结构」且「guest 恰好调了 locale」同时发生时
            // （TLog.w 无 Throwable 重载，2.1.43 合入时改用 e(tag, msg, t)）
            dev.twinbox.app.TLog.e(TAG, "fix fail", t);
        }
    }
}
