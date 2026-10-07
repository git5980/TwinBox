package com.lody.virtual.client.hook.utils;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.lody.virtual.client.hook.base.MethodProxy.getAppUserId;

/**
 * @author Lody
 *
 */
public class MethodParameterUtils {

	public static <T> T getFirstParam(Object[] args, Class<T> tClass) {
		if (args == null) {
			return null;
		}
		int index = ArrayUtils.indexOfFirst(args, tClass);
		if (index != -1) {
			return (T) args[index];
		}
		return null;
	}

	/**
	 * TwinBox 2.1.24：「调用者身份」guest → 宿主 的集中改写表。
	 *
	 * 键 = IActivityManager / IActivityTaskManager 的方法名；值 = 该方法签名里
	 * 「调用者包名」参数在 args 里的下标（照 Android 16 AIDL 实裁，
	 * aosp-mirror/platform_frameworks_base@android-16.0.0_r1）。
	 *
	 * 为什么必须改：AMS 侧对调用者身份有硬校验——
	 *   ATMS.startActivityAsUser / startActivities / startActivityAndWait /
	 *   startActivityWithConfig / startVoiceActivity / startAssistantActivity /
	 *   startActivityFromGameSession / moveTaskToFront / getAppTasks /
	 *   startActivity(s)InPackage 每次都 assertPackageMatchesCallingUid(callingPackage)
	 *   （ATMS.isSameApp：callingPackage 必须属于 callingUid）；
	 *   ActiveServices 在 BIND_EXTERNAL_SERVICE 分支做
	 *   isSameApp(callingPackage, callingUid) 校验。
	 * guest 包名不属于宿主 uid，原样透传到系统就是
	 * SecurityException: Permission Denial: package=<guest> does not belong to uid=<host>。
	 *
	 * 只改「调用者身份」这一列，绝不碰 intent / 目标包名等参数，所以和需要在钩子里
	 * 保留 guest 身份的业务（如 GetIntentSender 的 creator）互不干扰。
	 * 与具体是哪个 guest 应用无关——按运行时当前包名匹配，谁来都一样。
	 */
	private static final Map<String, Integer> CALLER_PKG_ARG = new HashMap<>();

	static {
		// ---- IActivityTaskManager：activity 启动家族 ----
		CALLER_PKG_ARG.put("startActivity", 1);
		CALLER_PKG_ARG.put("startActivityWithFeature", 1);
		CALLER_PKG_ARG.put("startActivities", 1);
		CALLER_PKG_ARG.put("startActivityAsUser", 1);
		CALLER_PKG_ARG.put("startActivityAsUserWithFeature", 1);
		CALLER_PKG_ARG.put("startActivityAndWait", 1);
		CALLER_PKG_ARG.put("startActivityWithConfig", 1);
		CALLER_PKG_ARG.put("startVoiceActivity", 0);
		CALLER_PKG_ARG.put("startAssistantActivity", 0);
		CALLER_PKG_ARG.put("startActivityFromGameSession", 1);
		CALLER_PKG_ARG.put("startActivityAsCaller", 1);
		CALLER_PKG_ARG.put("startActivityInPackage", 1);
		CALLER_PKG_ARG.put("startActivitiesInPackage", 1);
		CALLER_PKG_ARG.put("startNextMatchingActivity", 1);
		// ---- IActivityTaskManager：任务面 ----
		CALLER_PKG_ARG.put("moveTaskToFront", 1);
		CALLER_PKG_ARG.put("getAppTasks", 0);
		// ---- IActivityManager：服务面 ----
		CALLER_PKG_ARG.put("startService", 4);
		CALLER_PKG_ARG.put("peekService", 2);
		// ---- IActivityManager：服务绑定（BIND_EXTERNAL_SERVICE 分支会被校验）----
		CALLER_PKG_ARG.put("bindService", 6);
		CALLER_PKG_ARG.put("bindServiceInstance", 7);
		CALLER_PKG_ARG.put("bindIsolatedService", 7);
		// ---- IActivityManager：PendingIntent ----
		CALLER_PKG_ARG.put("getIntentSender", 1);
		CALLER_PKG_ARG.put("getIntentSenderWithFeature", 1);
	}

	/**
	 * 把 args 里「调用者包名」那一列从 guest 包名换成宿主包名。
	 * 只对表内方法生效；非 guest 进程（宿主主进程/引擎进程）、参数越界、
	 * 值不是当前 guest 包名时一律原样返回，绝不影响其他调用。
	 *
	 * @param method 正在调用的系统接口方法
	 * @param args   该方法的参数（就地修改）
	 */
	public static void replaceCallerPkg(Method method, Object[] args) {
		if (method == null || args == null) {
			return;
		}
		Integer idx = CALLER_PKG_ARG.get(method.getName());
		if (idx == null || idx < 0 || idx >= args.length) {
			return;
		}
		try {
			if (!VirtualCore.get().isVAppProcess()) {
				return;
			}
			String appPkg = com.lody.virtual.client.VClient.get().getCurrentPackage();
			if (appPkg == null || appPkg.isEmpty()) {
				return;
			}
			if (appPkg.equals(args[idx])) {
				args[idx] = VirtualCore.get().getHostPkg();
			}
		} catch (Throwable ignore) {
			// 身份改写失败不能反咬业务调用：保持原值放行
		}
	}

	public static String replaceFirstAppPkg(Object[] args) {
		if (args == null) {
			return null;
		}
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof String) {
				String value = (String) args[i];
				if (VirtualCore.get().isAppInstalled(value)) {
				    args[i] = VirtualCore.get().getHostPkg();
					return value;
				}
			}
		}
		return null;
	}

	public static void replaceLastUserId(Object[] args){
		int index = ArrayUtils.indexOfLast(args, Integer.class);
		if (index != -1) {
			int uid = (int) args[index];
			if (uid == getAppUserId()) {
				args[index] = VUserHandle.realUserId();
			}
		}
	}

	public static String replaceLastAppPkg(Object[] args) {
		int index = ArrayUtils.indexOfLast(args, String.class);
		if (index != -1) {
			String pkg = (String) args[index];
			args[index] = VirtualCore.get().getHostPkg();
			return pkg;
		}
		return null;
	}

	public static String replaceSequenceAppPkg(Object[] args, int sequence) {
		int index = ArrayUtils.indexOf(args, String.class, sequence);
		if (index != -1) {
			String pkg = (String) args[index];
			args[index] = VirtualCore.get().getHostPkg();
			return pkg;
		}
		return null;
	}

    public static int getParamsIndex(Class[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            Class obj = args[i];
            if (obj.equals(type)) {
                return i;
            }
        }
        return -1;
    }

	public static int getIndex(Object[] args, Class<?> type) {
		return getIndex(args, type, 0);
	}

	public static int getIndex(Object[] args, Class<?> type, int start) {
		for (int i = start; i < args.length; i++) {
			Object obj = args[i];
			if (obj != null && obj.getClass() == type) {
				return i;
			}
			if (type.isInstance(obj)) {
				return i;
			}
		}
		return -1;
	}

	public static Class<?>[] getAllInterface(Class clazz){
		HashSet<Class<?>> classes = new HashSet<>();
		getAllInterfaces(clazz,classes);
		Class<?>[] result=new Class[classes.size()];
		classes.toArray(result);
		return result;
	}


	public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
		Class<?>[] classes = clazz.getInterfaces();
		if (classes.length != 0) {
			interfaceCollection.addAll(Arrays.asList(classes));
		}
		if (clazz.getSuperclass() != Object.class) {
			getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
		}
	}


}
