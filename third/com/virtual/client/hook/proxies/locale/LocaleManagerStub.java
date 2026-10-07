package com.lody.virtual.client.hook.proxies.locale;

import android.app.LocaleManager;
import android.content.Context;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.annotations.Inject;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;

import dev.twinbox.app.TLog;

import mirror.android.app.ILocaleManager;

/**
 * TwinBox 2.1.43：ILocaleManager 服务钩子（guest per-app locale 家族）。
 *
 * <p>案情（真机，Android 16 / OnePlus PKR110 / SDK 36，`dev.twinbox.app` uid 10041）：
 * <pre>
 * FATAL EXCEPTION: Thread-6
 * java.lang.IllegalArgumentException: Unknown package: com.deepseek.chat for user 0
 *   at android.app.ILocaleManager$Stub$Proxy.setApplicationLocales(ILocaleManager.java:219)
 *   at android.app.LocaleManager.setApplicationLocales(LocaleManager.java:111)
 *   Caused by: android.os.RemoteException
 *     at com.android.server.locales.LocaleManagerService.isPackageOwnedByCaller(...:375)
 * </pre>
 *
 * <p>根因：guest 包名直接打到真 LocaleManagerService。系统按「调用者 uid 是否拥有该
 * 包名」校验（isPackageOwnedByCaller），guest 进程的 uid 是宿主真身 10041，宿主不拥有
 * `com.deepseek.chat` → 抛 IllegalArgumentException 且调用方没有 try/catch → 进程死。
 * 与 2.1.33 音频那次（AppOps PLAY_AUDIO 静音）同构：**guest 包名 + 宿主 uid** 的组合
 * 泄漏给系统服务；音频是静音，这次直接 FATAL。
 *
 * <p>为什么 2.1.40/2.1.41 的按 context 修补（{@code com.lody.virtual.client.fixer.LocaleFixer}）
 * 没兜住：LocaleManager 由 SystemServiceRegistry 的 {@code CachedServiceFetcher} 创建，
 * **每个 ContextImpl 一份实例缓存**，{@code fixContext} 只覆盖 Application / Activity /
 * Service 三类 context。guest 从其它 context（createConfigurationContext、
 * createPackageContext、Provider context 等）拿到的 LocaleManager 没被 patch，
 * 里面的 mService 仍是真 binder → 照样打到系统。
 *
 * <p>修法（系统性，不挑应用）：在 **binder 层**接管服务（AlarmManagerStub /
 * LocationManagerStub 同款），之后任何 context、任何线程、任何时机拿到的
 * LocaleManager 都带着钩子；再顺手把已经缓存住的实例逐个换 mService 兜底。
 *
 * @see com.lody.virtual.client.hook.proxies.locale.MethodProxies
 */
@Inject(MethodProxies.class)
public class LocaleManagerStub extends BinderInvocationProxy {

    public LocaleManagerStub() {
        super(ILocaleManager.Stub.asInterface, Context.LOCALE_SERVICE);
    }

    @Override
    public void inject() throws Throwable {
        super.inject();
        patchCachedManagers();
    }

    /**
     * sCache 换 binder 只影响「之后新建的 LocaleManager」；已经被 context 缓存住的实例
     * 各换一次 mService。LocationManagerStub/AlarmManagerStub 同款操作。
     */
    private void patchCachedManagers() {
        // ① 宿主/vApp 进程主 context 的缓存实例
        try {
            LocaleManager lm = (LocaleManager) VirtualCore.get().getContext()
                    .getSystemService(Context.LOCALE_SERVICE);
            if (lm != null) {
                mirror.android.app.LocaleManager.mService.set(lm, getInvocationStub().getProxyInterface());
            }
        } catch (Throwable t) {
            TLog.w("V|Locale", "patch host-cached LocaleManager fail: " + t);
        }
        // ② guest Application context 的缓存实例（另一份 ContextImpl）
        try {
            android.app.Application app = com.lody.virtual.client.VClient.get().getCurrentApplication();
            if (app != null) {
                LocaleManager lm = (LocaleManager) app.getSystemService(Context.LOCALE_SERVICE);
                if (lm != null) {
                    mirror.android.app.LocaleManager.mService.set(lm, getInvocationStub().getProxyInterface());
                }
            }
        } catch (Throwable t) {
            TLog.w("V|Locale", "patch app-cached LocaleManager fail: " + t);
        }
    }
}
