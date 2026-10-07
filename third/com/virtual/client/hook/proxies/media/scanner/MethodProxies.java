package com.lody.virtual.client.hook.proxies.media.scanner;

import android.text.TextUtils;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;

/**
 * TwinBox 2.1.57：media_scanner 服务的包名/路径方法钩子。
 *
 * 威胁模型（用户需求：容器内数据不出容器）：
 * guest 调 MediaScannerConnection.scanFile(path, mime) → 系统 MediaScanner
 * 扫描该路径并收进 MediaStore → 出现在真机相册/云备份。
 *
 * 判定：路径落在容器目录树内 → 放行（宿主 app-private 目录，系统扫了也
 * 不会出现在用户可见相册）；路径指向真 sdcard → 吞掉（返回 null，API
 * 契约允许），留 W 级日志。IO 重定向开启后裸路径也会被拉进容器树，
 * 此钩子作为「主动请求扫描」的最后防线。
 */
class MethodProxies {

    private MethodProxies() {
    }

    /** 路径是否在容器目录树内（宿主数据目录下即视为墙内） */
    private static boolean insideBox(String path) {
        if (TextUtils.isEmpty(path)) {
            return true; // 空路径无威胁，放行
        }
        String hostData = VirtualCore.get().getContext().getPackageName();
        // 容器根：/data/data/<host>/virtual/ 与 /data/user/0/<host>/virtual/
        return path.contains("/" + hostData + "/")
                || path.startsWith("/data/data/" + hostData)
                || path.startsWith("/data/user/0/" + hostData);
    }

    private static String firstPathArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof String) {
                return (String) a;
            }
        }
        return null;
    }

    static class ScanFile extends MethodProxy {

        @Override
        public String getMethodName() {
            return "scanFile";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            String path = firstPathArg(args);
            if (!insideBox(path)) {
                dev.twinbox.app.TLog.w("V|MScan", "scanFile swallowed (outside box): "
                        + path + " pkg=" + MethodProxy.getAppPkg());
                return null;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class RequestScanFile extends MethodProxy {

        @Override
        public String getMethodName() {
            return "requestScanFile";
        }

        @Override
        public Object call(Object who, Method method, Object[] args) throws Throwable {
            String path = firstPathArg(args);
            if (!insideBox(path)) {
                dev.twinbox.app.TLog.w("V|MScan", "requestScanFile swallowed (outside box): "
                        + path + " pkg=" + MethodProxy.getAppPkg());
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
