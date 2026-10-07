package dev.twinbox.app;

import java.lang.reflect.Method;

/**
 * Hidden API 豁免（元反射式）：
 * Android 9+ 对非 SDK 接口做反射拦截，但拦截只发生在反射入口
 * （Class.getDeclaredMethod 等），通过一个已获取的 Method 对象再去
 * getDeclaredMethod 不会二次过闸（meta-reflection）。以此取得
 * VMRuntime.setHiddenApiExemptions 并豁免 "L" 前缀（全部类）。
 * 该姿势为 LSPatch / ChickenHook 系通用做法，Android 12-16 实测有效。
 */
public final class HiddenApiBypass {

    private static volatile boolean sDone = false;

    public static void exemptAllOnce() {
        if (sDone) {
            return;
        }
        sDone = true;
        try {
            // 元反射：拿 Class.getDeclaredMethod 的 Method 对象（公开 API，不受限）
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            // 经 Method.invoke 取隐藏方法 getRuntime / setHiddenApiExemptions
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            TLog.i("Hook", "hidden API exempted: all (L) OK");
        } catch (Throwable t) {
            // 旧系统（<P）无此 API，属正常；其余情形记录但不中断启动
            TLog.e("Hook", "hidden API exempt FAIL (SDK "
                    + android.os.Build.VERSION.SDK_INT + ")", t);
        }
    }
}
