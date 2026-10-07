package com.lody.virtual.client.hook.proxies.window.session;

import android.os.IInterface;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.base.MethodInvocationStub;

import java.lang.reflect.Method;

import mirror.android.view.WindowManagerGlobal;

/**
 * @author Lody
 */
public class WindowSessionPatch extends MethodInvocationProxy<MethodInvocationStub<IInterface>> {
    private static final int ADD_PERMISSION_DENIED = WindowManagerGlobal.ADD_PERMISSION_DENIED != null
            ? WindowManagerGlobal.ADD_PERMISSION_DENIED.get() : -8;

	public WindowSessionPatch(IInterface session) {
		super(new MethodInvocationStub<>(session));
	}

	@Override
    public void onBindMethods() {
        addMethodProxy(new BaseMethodProxy("add"));
        addMethodProxy(new BaseMethodProxy("addToDisplay") {
            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                if (isDrawOverlays() && VirtualCore.getConfig().isDisableDrawOverlays(getAppPkg())) {
                    return ADD_PERMISSION_DENIED;
                }
                return super.call(who, method, args);
            }
        });
        // TwinBox 2.1.26：Android 12+ 起 ViewRootImpl 实际调用的是 addToDisplayAsUser
        // （IWindowSession.aidl 里带 WindowManager.LayoutParams 的方法有：
        // addToDisplay / addToDisplayAsUser / addToDisplayWithoutInputChannel /
        // relayout / relayoutAsync）。老代码只注册了 add/addToDisplay 等旧名字，
        // 新名字一个都没兜住 → BaseMethodProxy.beforeCall 里的
        // attrs.packageName = getHostPkg() 从不执行 → WMS 的
        // doesAddToastWindowRequireToken 拿 Toast 窗口的 guest 包名对 uid，
        // 直接 SecurityException: Package <guest> not in UID <host>，
        // Toast 一弹整个 guest 进程就走了（见 crash-com-puretone-player-
        // 05_10-20-49-57_453.log：Adding window failed ← addToDisplayAsUser）。
        addMethodProxy(new BaseMethodProxy("addToDisplayAsUser") {
            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                if (isDrawOverlays() && VirtualCore.getConfig().isDisableDrawOverlays(getAppPkg())) {
                    return ADD_PERMISSION_DENIED;
                }
                return super.call(who, method, args);
            }
        });
        addMethodProxy(new BaseMethodProxy("addToDisplayWithoutInputChannel"));
        addMethodProxy(new BaseMethodProxy("addWithoutInputChannel"));
		addMethodProxy(new Relayout("relayout"));
		addMethodProxy(new Relayout("relayoutAsync"));
	}


	@Override
	public void inject() throws Throwable {
		// <EMPTY>
	}

	@Override
	public boolean isEnvBad() {
		return getInvocationStub().getProxyInterface() != null;
	}
}
