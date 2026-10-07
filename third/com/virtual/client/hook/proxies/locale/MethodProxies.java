package com.lody.virtual.client.hook.proxies.locale;

import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;

/**
 * TwinBox 2.1.43：ILocaleManager 里「按 (uid, packageName) 所有权校验」的四兄弟钩子。
 *
 * <p>服务端校验（android-16.0.0_r1
 * services/core/java/com/android/server/locales/LocaleManagerService.java:375）：
 * <pre>
 *   private boolean isPackageOwnedByCaller(String packageName, int userId) {
 *       ... "Unknown package: " + packageName + " for user " + userId  ← IllegalArgumentException
 *   }
 * </pre>
 * guest 进程里这些方法全部带着 guest 包名打到真系统服务：调用者 uid 是宿主真身
 * （ContextFixer 改写过的 attribution），包名却是 guest —— 宿主当然不"拥有"guest，
 * 于是读和写都抛 IllegalArgumentException 且没有客户端兜底，直接 FATAL。
 *
 * <p>已实测的两种死法：
 * <ul>
 *   <li>setApplicationLocales / getApplicationLocales：DeepSeek 后台线程崩
 *       （06_10-23-57、07_10-10-09-25 两份真机 crash，Thread-6）；
 *       setApplicationLocales 写路径也被 getApplicationLocales 读比对放大；</li>
 *   <li>setOverrideLocaleConfig / getOverrideLocaleConfig：同一个校验函数、
 *       同样的 packageName 首参，换个名字照样炸——只是目前没有 guest 触发到，
 *       属于这条链上的同构口子。</li>
 * </ul>
 *
 * <p>容器语义：guest 在容器里没有系统身份（per-app locale 本就按真包名存在系统侧），
 * 无法真正落地。读返回空 = 「无 per-app 设置」→ 语言跟随系统；写吞掉 = 应用以为
 * 设置成功（返回值和真系统一样是 void）。宿主自己的调用（ContextFixer 已把
 * mBasePackageName 换成宿主，应用走无参 API 时传的就是宿主包名）一律原样透传。
 */
class MethodProxies {

    private MethodProxies() {
    }

    /**
     * @return 首参是不是「非宿主」包名（即 guest/外来包名）。
     * 宿主自身调用或无参调用（args[0] 不是 String）一律放行——宿主对该包名的调用
     * 在系统侧合法，不需要容器插手。
     */
    private static boolean isForeignPkg(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return false;
        }
        return !MethodProxy.getHostPkg().equals(args[0]);
    }

    private static String pkgOf(Object[] args) {
        return args != null && args.length > 0 ? String.valueOf(args[0]) : null;
    }

    /**
     * void setApplicationLocales(String packageName, int userId, LocaleList locales,
     * boolean fromDelegate) —— Android 16 四参。别按下标取参（13/14 是三参），
     * 这里只需要首参包名，天然免疫。
     */
    static class SetApplicationLocales extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setApplicationLocales";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            if (isForeignPkg(args)) {
                dev.twinbox.app.TLog.i("V|Locale", "setApplicationLocales(" + pkgOf(args)
                        + ") swallowed: no system identity in box, per-app locale unsupported");
                return null;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    /**
     * LocaleList getApplicationLocales(String packageName, int userId)。
     * 读路径同样炸（isPackageOwnedByCaller 先查所有权再返回），返回空 LocaleList：
     * 「没有 per-app 语言」，应用回退系统语言——容器里的正确语义。
     */
    static class GetApplicationLocales extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getApplicationLocales";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            if (isForeignPkg(args)) {
                dev.twinbox.app.TLog.i("V|Locale", "getApplicationLocales(" + pkgOf(args)
                        + ") -> empty (no per-app locale in box)");
                return android.os.LocaleList.getEmptyLocaleList();
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    /** void setOverrideLocaleConfig(String packageName, int userId, LocaleConfig localeConfig) */
    static class SetOverrideLocaleConfig extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setOverrideLocaleConfig";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            if (isForeignPkg(args)) {
                dev.twinbox.app.TLog.i("V|Locale", "setOverrideLocaleConfig(" + pkgOf(args)
                        + ") swallowed: no system identity in box");
                return null;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    /**
     * LocaleConfig getOverrideLocaleConfig(String packageName, int userId)。
     * 返回 null = 「未覆盖」，与 AOSP 的 @Nullable 契约一致（LocaleManager
     * 层也只是把它透给调用方）。
     */
    static class GetOverrideLocaleConfig extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getOverrideLocaleConfig";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            if (isForeignPkg(args)) {
                dev.twinbox.app.TLog.i("V|Locale", "getOverrideLocaleConfig(" + pkgOf(args)
                        + ") -> null (no override in box)");
                return null;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }
}
