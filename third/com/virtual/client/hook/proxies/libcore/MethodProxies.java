package com.lody.virtual.client.hook.proxies.libcore;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.libcore.io.Os;

/**
 * @author Lody
 */

class MethodProxies {

    static class Lstat extends Stat {

        @Override
        public String getMethodName() {
            return "lstat";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            if (result != null) {
                Reflect pwd = Reflect.on(result);
                int uid = pwd.get("st_uid");
                if (uid == VirtualCore.get().myUid()) {
                    pwd.set("st_uid", VClient.get().getVUid());
                }
            }
            return result;
        }
    }

    static class Fstat extends Stat {

        @Override
        public String getMethodName() {
            return "fstat";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            if (result != null) {
                Reflect pwd = Reflect.on(result);
                int uid = pwd.get("st_uid");
                if (uid == VirtualCore.get().myUid()) {
                    pwd.set("st_uid", VClient.get().getVUid());
                }
            }
            return result;
        }
    }
    static class Getpwnam extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getpwnam";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect pwd = Reflect.on(result);
                    int uid = pwd.get("pw_uid");
                    if (uid == VirtualCore.get().myUid()) {
                        pwd.set("pw_uid", VClient.get().getVUid());
                    }
                }
                return result;
            }
        }

    static class GetUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getuid";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            int uid = (int) result;
            return NativeEngine.onGetUid(uid);
        }
    }

    static class GetsockoptUcred extends MethodProxy {
            @Override
            public String getMethodName() {
                return "getsockoptUcred";
            }

            @Override
            public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
                if (result != null) {
                    Reflect ucred = Reflect.on(result);
                    int uid = ucred.get("uid");
                    if (uid == VirtualCore.get().myUid()) {
                        ucred.set("uid", getBaseVUid());
                    }
                }
                return result;
            }
        }

    static class Stat extends MethodProxy {

        private static Field st_uid;

        static {
            try {
                Method stat = Os.TYPE.getMethod("stat", String.class);
                Class<?> StructStat = stat.getReturnType();
                st_uid = StructStat.getDeclaredField("st_uid");
                st_uid.setAccessible(true);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            // TwinBox 2.1.19：Android 16 真机上此钩子反复抛异常（2.1.18 日志：
            // Hook crash, fallback to origin method: Os.stat ITE）。字段类型/
            // OEM 变体差异不再让 guest 冒险——失败时保留真实 stat 结果静默降级。
            if (result == null) {
                return result;
            }
            try {
                Object v = st_uid.get(result);
                if (v instanceof Integer && (Integer) v == VirtualCore.get().myUid()) {
                    st_uid.set(result, getBaseVUid());
                }
            } catch (Throwable ignore) {
                // 保留真实 stat 结果（仅 uid 虚拟化失效，权限校验不受影响）
            }
            return result;
        }

        @Override
        public String getMethodName() {
            return "stat";
        }
    }
}
