package mirror.android.app;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/**
 * TwinBox 2.1.43：android.app.ILocaleManager（API 33+ 的隐藏接口）。
 *
 * AIDL（android-16.0.0_r1 / core/java/android/app/ILocaleManager.aidl，逐字）：
 * <pre>
 *   void     setApplicationLocales(String packageName, int userId, in LocaleList locales, boolean fromDelegate);
 *   LocaleList getApplicationLocales(String packageName, int userId);
 *   LocaleList getSystemLocales();
 *   void     setOverrideLocaleConfig(String packageName, int userId, in LocaleConfig localeConfig);
 *   LocaleConfig getOverrideLocaleConfig(String packageName, int userId);
 * </pre>
 * 注意 setApplicationLocales 在 16 上是 **4 参**（userId 在 pkg 之后、fromDelegate
 * 垫尾），13/14 曾经是 3 参（无 fromDelegate）——钩子按方法名+按类型取参，别写下标。
 */
public class ILocaleManager {
    public static Class<?> TYPE = RefClass.load(ILocaleManager.class, "android.app.ILocaleManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(Stub.class, "android.app.ILocaleManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
