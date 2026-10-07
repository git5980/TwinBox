package mirror.android.app;

import android.os.IInterface;

import mirror.RefClass;
import mirror.RefObject;

/**
 * TwinBox 2.1.43：android.app.LocaleManager（API 33+ 公开类）。
 *
 * 与 {@link mirror.android.location.LocationManager} 同构：manager 按 context 缓存
 * 服务接口在私有字段 mService 里（android-16.0.0_r1 的 LocaleManager 仍是
 * {@code private ILocaleManager mService}）。换 sCache 只管「之后新建的实例」，
 * 已经被 context 缓存住的实例要逐个把 mService 换成代理——AlarmManagerStub /
 * LocationManagerStub 都是这么做的。
 */
public class LocaleManager {
    public static Class<?> TYPE = RefClass.load(LocaleManager.class, "android.app.LocaleManager");
    public static RefObject<IInterface> mService;
}
