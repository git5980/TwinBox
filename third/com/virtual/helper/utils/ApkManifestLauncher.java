package com.lody.virtual.helper.utils;

import android.content.res.XmlResourceParser;

import org.xmlpull.v1.XmlPullParser;

/**
 * TwinBox 2.1.45：从容器内 APK 的 AndroidManifest.xml 里读出「真正的启动入口」。
 *
 * 为什么需要它
 * ------------
 * Android 12+ 上 {@code PackageParserEx.parsePackage} 走的是公开 API 桥
 * （{@code PackageManager.getPackageArchiveInfo}，见 PackageParserEx.parsePackageModern），
 * 这条路拿不到任何 intent-filter —— 容器里 VPackage 组件的 intents 全是空的，
 * 桌面/启动链只能靠人工挂一个 MAIN/LAUNCHER 的假 filter。
 *
 * 原来的判定是「宿主 PM 反查得到就用宿主给的，否则取 APK 里第一个 activity」。
 * SAF 装进容器的 APK，宿主必然没有同包 → 必然落到「第一个 activity」，而 APK 里
 * 第一个 activity 常常是 wxapi.WXEntryActivity 这类透明回调页：
 *
 * <pre>
 * 07_10-12-39-50_170.log（DeepSeek / com.deepseek.chat）：
 *   TwinBox/VBox:      launch: com.deepseek.chat userId=0
 *   TwinBox/V|Core:    getLaunchIntent q2(LAUNCHER) size=1
 *   TwinBox/VBox:      query launcher: ComponentInfo{com.deepseek.chat/com.deepseek.chat.wxapi.WXEntryActivity}
 *   TransparentWindowDetector: Detect Empty window:com.deepseek.chat/com.deepseek.chat.wxapi.WXEntryActivity
 * </pre>
 *
 * 用户看到的就是「不崩、没报错，只有一个透明界面」。
 *
 * 实现方式
 * --------
 * 用隐藏 API（{@code AssetManager.addAssetPath(apk)} +
 * {@code openXmlResourceParser("AndroidManifest.xml")}）借**框架自己的二进制 XML 解析器**
 * 解码 manifest（不自己写 axml 解析器），只在里面找声明了 MAIN + LAUNCHER 的
 * activity / activity-alias。App 启动时已经调过
 * {@code HiddenApiBypass.exemptAllOnce()}（VMRuntime.setHiddenApiExemptions("L")），
 * 这些隐藏 API 的反射可用；任何一步失败都只记日志返回 null，由调用方降级，
 * 绝不影响原有可用路径。
 *
 * @see com.lody.virtual.server.pm.parser.PackageParserEx#parsePackageModern(java.io.File)
 */
public final class ApkManifestLauncher {

    private static final String TAG = "ApkManifestLauncher";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String ACTION_MAIN = "android.intent.action.MAIN";
    private static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    private static final String MANIFEST_ENTRY = "AndroidManifest.xml";

    private ApkManifestLauncher() {
    }

    /**
     * @param apkPath     容器内 APK 路径
     * @param packageName 目标包名（用于把 {@code .Foo} / {@code Foo} 展开成完整类名）
     * @return manifest 里带 MAIN + LAUNCHER 的组件完整类名；拿不到返回 {@code null}
     */
    public static String findLauncher(String apkPath, String packageName) {
        if (apkPath == null || packageName == null) {
            return null;
        }
        XmlResourceParser parser = null;
        Object am = null;
        try {
            am = newAssetManager(apkPath);
            if (am == null) {
                return null;
            }
            java.lang.reflect.Method openXml = am.getClass()
                    .getDeclaredMethod("openXmlResourceParser", String.class);
            openXml.setAccessible(true);
            parser = (XmlResourceParser) openXml.invoke(am, MANIFEST_ENTRY);
            return scan(parser, packageName);
        } catch (Throwable t) {
            VLog.w(TAG, "scan manifest fail (" + apkPath + "): " + t);
        } finally {
            // 先关 parser 再关 AssetManager：这两个都是 native 资源，别指望 GC
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignore) {
                }
            }
            if (am != null) {
                try {
                    java.lang.reflect.Method close = am.getClass().getDeclaredMethod("close");
                    close.setAccessible(true);
                    close.invoke(am);
                } catch (Throwable ignore) {
                }
            }
        }
        return null;
    }

    private static Object newAssetManager(String apkPath) throws Exception {
        Class<?> cls = Class.forName("android.content.res.AssetManager");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object am = ctor.newInstance();
        java.lang.reflect.Method addAssetPath = cls.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        Object cookie = addAssetPath.invoke(am, apkPath);
        // addAssetPath 返回 0 = 这个路径没被加进去（文件不存在 / 不是 APK）
        if (cookie instanceof Integer && ((Integer) cookie).intValue() == 0) {
            VLog.w(TAG, "addAssetPath returned 0, skip: " + apkPath);
            return null;
        }
        return am;
    }

    /** 单趟扫 manifest：activity/activity-alias 下的 intent-filter 里找 MAIN + LAUNCHER */
    private static String scan(XmlResourceParser parser, String packageName) throws Exception {
        String current = null;          // 当前 activity / activity-alias 的 android:name（原样）
        boolean alias = false;          // 当前元素是不是 activity-alias
        String aliasTarget = null;      // activity-alias 的 android:targetActivity
        boolean sawMain = false;
        boolean sawLauncher = false;
        String aliasLauncher = null;            // alias 命中时的备选（真 activity 优先）
        String aliasLauncherTarget = null;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                    current = attr(parser, "name");
                    alias = "activity-alias".equals(tag);
                    aliasTarget = alias ? attr(parser, "targetActivity") : null;
                    sawMain = false;
                    sawLauncher = false;
                } else if ("intent-filter".equals(tag)) {
                    sawMain = false;
                    sawLauncher = false;
                } else if (current != null && "action".equals(tag)) {
                    if (ACTION_MAIN.equals(attr(parser, "name"))) {
                        sawMain = true;
                    }
                } else if (current != null && "category".equals(tag)) {
                    if (CATEGORY_LAUNCHER.equals(attr(parser, "name"))) {
                        sawLauncher = true;
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("intent-filter".equals(tag)) {
                    if (sawMain && sawLauncher && current != null) {
                        String full = expand(packageName, current);
                        if (!alias) {
                            // 真 activity 的 launcher 就是它，直接收工
                            VLog.i(TAG, "launcher from manifest: " + full);
                            return full;
                        }
                        if (aliasLauncher == null) {
                            aliasLauncher = full;
                            aliasLauncherTarget = expand(packageName, aliasTarget);
                        }
                    }
                    sawMain = false;
                    sawLauncher = false;
                } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                    current = null;
                    alias = false;
                }
            }
            event = parser.next();
        }
        // alias 优先返回 targetActivity：容器 VPackage 里只有真 activity 的 ActivityInfo，
        // alias 本身启动不了（getActivityInfo 会返回 null）
        if (aliasLauncherTarget != null) {
            VLog.i(TAG, "launcher from manifest alias target: " + aliasLauncherTarget);
            return aliasLauncherTarget;
        }
        if (aliasLauncher != null) {
            VLog.w(TAG, "launcher is an activity-alias without targetActivity: " + aliasLauncher);
            return aliasLauncher;
        }
        return null;
    }

    /** android:name / android:targetActivity 取值：先按 namespace 查，取不到再按属性名扫 */
    private static String attr(XmlResourceParser parser, String name) {
        try {
            String v = parser.getAttributeValue(ANDROID_NS, name);
            if (v != null) {
                return v;
            }
        } catch (Throwable ignore) {
        }
        try {
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                if (name.equals(parser.getAttributeName(i))) {
                    return parser.getAttributeValue(i);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** manifest 里的类名展开：{@code .Foo} → {@code pkg.Foo}；{@code Foo} → {@code pkg.Foo} */
    private static String expand(String packageName, String cls) {
        if (cls == null || cls.length() == 0) {
            return null;
        }
        if (cls.charAt(0) == '.') {
            return packageName + cls;
        }
        if (cls.indexOf('.') < 0) {
            return packageName + "." + cls;
        }
        return cls;
    }
}
