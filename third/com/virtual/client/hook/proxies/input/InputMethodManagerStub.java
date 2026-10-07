package com.lody.virtual.client.hook.proxies.input;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.annotations.Inject;

import dev.twinbox.app.TLog;

import mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker;
import mirror.com.android.internal.view.inputmethod.InputMethodManager;

/**
 * @author Lody
 */
@Inject(MethodProxies.class)
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)

public class InputMethodManagerStub extends BinderInvocationProxy {

	public InputMethodManagerStub() {
		super(
				InputMethodManager.mService.get(
						VirtualCore.get().getContext().getSystemService(Context.INPUT_METHOD_SERVICE)),
				Context.INPUT_METHOD_SERVICE);
	}

	@Override
	public void inject() throws Throwable {
		super.inject();
		patchCachedManagers();
	}

	/**
	 * TwinBox 2.1.46：Android 16 起客户端 InputMethodManager 不再走自己的
	 * mService，所有输入 IPC 都走
	 * {@code android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache}
	 * ——一个**进程级静态缓存**，第一次输入 IPC 时用
	 * {@code ServiceManager.getService("input_method")} 解析后就固定住。
	 *
	 * sCache 换 binder（super.inject()）只保证「之后新建的 IMM 实例」带钩子；
	 * 这个静态缓存一旦被真 binder 占住，我们就再也接不到任何输入 IPC，
	 * {@code MethodProxies.StartInputOrWindowGainedFocus} 里的 EditorInfo 包名
	 * 改写随之整条失效。所以这里把静态缓存也换成代理（拿不到就只靠 sCache）。
	 *
	 * 另外把已经缓存住的 IMM 实例的 mService 逐个换掉（AlarmManager 同款）。
	 */
	private void patchCachedManagers() {
		// ① Android 16 的进程级静态服务缓存（关键）
		try {
			IInputMethodManagerGlobalInvoker.sServiceCache.set(
					null, getInvocationStub().getProxyInterface());
			TLog.i("V|IMMS", "invoker sServiceCache -> proxy "
					+ "(Android16 InputMethodManagerGlobal path)");
		} catch (Throwable t) {
			// API < 16 或 ROM 改了字段名：不致命，sCache 仍然覆盖新实例
			TLog.w("V|IMMS", "patch invoker sServiceCache skip: " + t);
		}
		// ② 已缓存实例（宿主主 context）
		try {
			Object inputMethodManager = VirtualCore.get().getContext()
					.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (inputMethodManager != null) {
				InputMethodManager.mService.set(inputMethodManager,
						getInvocationStub().getProxyInterface());
			}
		} catch (Throwable t) {
			TLog.w("V|IMMS", "patch host-cached IMM fail: " + t);
		}
		// ③ guest Application context 的缓存实例（另一份 ContextImpl）
		try {
			android.app.Application app = com.lody.virtual.client.VClient.get().getCurrentApplication();
			if (app != null) {
				Object inputMethodManager = app.getSystemService(Context.INPUT_METHOD_SERVICE);
				if (inputMethodManager != null) {
					InputMethodManager.mService.set(inputMethodManager,
							getInvocationStub().getProxyInterface());
				}
			}
		} catch (Throwable t) {
			TLog.w("V|IMMS", "patch app-cached IMM fail: " + t);
		}
	}


	@Override
	public boolean isEnvBad() {
		Object inputMethodManager = getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		return InputMethodManager
				.mService.get(inputMethodManager) != getInvocationStub().getBaseInterface();
	}

}
