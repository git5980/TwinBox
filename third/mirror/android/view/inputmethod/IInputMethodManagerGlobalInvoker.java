package mirror.android.view.inputmethod;

import android.os.IInterface;

import mirror.RefClass;
import mirror.RefObject;

/**
 * TwinBox 2.1.46：android.view.inputmethod.IInputMethodManagerGlobalInvoker 的
 * 进程级静态服务缓存。
 *
 * <p>Android 16 起 {@code InputMethodManager} **自己不再持有 mService**
 * （AOSP 源码里 `grep "mService\." InputMethodManager.java` 一无所获），
 * 所有输入 IPC——startInputOrWindowGainedFocus / showSoftInput / hideSoftInput /
 * addClient / reportPerceptible……——全部改走这个隐藏类的静态字段：
 *
 * <pre>
 *   private static volatile IInputMethodManager sServiceCache = null;
 *   static IInputMethodManager getService() {
 *       IInputMethodManager service = sServiceCache;
 *       if (service != null) return service;
 *       service = IInputMethodManager.Stub.asInterface(
 *               ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
 *       sServiceCache = service;      // ← 进程级，只解析一次
 *       return service;
 *   }
 * </pre>
 *
 * 含义：容器只在 input_method 的 sCache 换 binder 只对「之后新建的 IMM 实例」有效，
 * 而这个静态缓存一旦被真 binder 占住（任何一次输入 IPC 都会占住），
 * 我们的 IMMS 钩子就整条不再被调用——EditorInfo 包名改写随之失效，
 * guest 里的输入体验就会坏（键盘起不来的那一类）。
 * 这个类是包级可见（final class，无 public），只能反射，故放 mirror。
 */
public class IInputMethodManagerGlobalInvoker {
    public static Class<?> TYPE = RefClass.load(IInputMethodManagerGlobalInvoker.class,
            "android.view.inputmethod.IInputMethodManagerGlobalInvoker");

    public static RefObject<IInterface> sServiceCache;
}
