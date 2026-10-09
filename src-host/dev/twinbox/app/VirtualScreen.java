package dev.twinbox.app;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

/**
 * TwinBox 2.2.0：虚拟屏管理（VMOS 架构复用·显示链路）。
 *
 * VMOS 分析结论（vmos pro 3.1.4 逆向）：VM 画面 = 宿主进程内视频流
 * （libmci.so InputVideoPack/ InputTouchReq_pack），悬浮窗/全屏/拖动全是
 * View 层动画。TwinBox 的 guest 是真 app，画面在 SurfaceFlinger——复刻路：
 *
 *   guest task 启动到宿主创建的 VirtualDisplay
 *     → 虚拟屏 Surface = 悬浮窗 TextureView（画面流进浮窗）
 *     → 拖动 = 悬浮窗尺寸跟手（真画面跟手，零 task 切换）
 *
 * 本类只管屏：创建/销毁/Surface 绑定。触摸通道见 FloatingService（AIDL 链）。
 *
 * 生命周期：屏随宿主前台服务常驻（guest 在虚拟屏上保持 RESUMED =
 * 用户理解的"后台运行"，VMOS 同款语义）；宿主进程死了屏自动没了。
 */
public final class VirtualScreen {

    private static final String TAG = "VScreen";
    private static final String NAME = "TwinBoxWindow";

    private static VirtualDisplay sDisplay;
    private static int sDisplayId = -1;
    private static int sW, sH, sDpi;
    /** 2.2.3：MediaProjection 通道（授权 token） */
    private static android.media.projection.MediaProjection sProjection;
    private static Intent sPendingResult;
    private static int sPendingResultCode;

    private VirtualScreen() {
    }

    /** 虚拟屏是否已建。 */
    public static boolean ready() {
        return sDisplayId >= 0;
    }

    public static int displayId() {
        return sDisplayId;
    }

    /**
     * 2.2.3：创建（或复用）虚拟屏——MediaProjection 通道。
     *
     * 路线考古（真机日志链）：三方 createVirtualDisplay 两个 flag 全死——
     *  - OWN_CONTENT_ONLY → private 屏，ColorOS 拒引擎 :x 进程 launch（SecurityException
     *    "Permission Denial ... with launchDisplayId"）
     *  - PUBLIC → 要 ADD_MIRROR_DISPLAY/CAPTURE_VIDEO_OUTPUT（镜像类权限，三方无）
     * 正解 = MediaProjection：用户在系统弹窗点一次"立即开始"，getMediaProjection
     * 拿到带 token 的 projection → projection.createVirtualDisplay ——这个屏
     * 天生允许跨进程 launch（Cast 同款机制）。
     *
     * 必须先有 projection（授权回执 onProjectionResult 存 Intent），没授权
     * 返回 -1（调用方走 v1 窗帘）。
     *
     * @return displayId；-1 = 未授权/失败
     */
    public static int ensure(Context ctx) {
        if (sDisplayId >= 0) {
            return sDisplayId;
        }
        if (sProjection == null) {
            if (sPendingResult == null) {
                TLog.w(TAG, "ensure: no projection (user not authorized)");
                return -1;
            }
            try {
                android.media.projection.MediaProjectionManager mpm =
                        (android.media.projection.MediaProjectionManager)
                                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                sProjection = mpm.getMediaProjection(sPendingResultCode, sPendingResult);
                sProjection.registerCallback(new android.media.projection.MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        TLog.w(TAG, "projection stopped by system/user");
                        release();
                    }
                }, null);
            } catch (Throwable t) {
                TLog.e(TAG, "getMediaProjection fail", t);
                sPendingResult = null;
                return -1;
            }
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return -1;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            DisplayMetrics dm2 = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm2);
            sW = p.x;
            sH = p.y;
            sDpi = dm2.densityDpi;
            // OWN_CONTENT_ONLY + projection token = 免镜像权限 + 可跨进程 launch。
            // android-34 平台 jar 的签名：flags 在 Surface 前。
            sDisplay = sProjection.createVirtualDisplay(
                    NAME + "@" + android.os.Process.myUid(),
                    sW, sH, sDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null /* surface 延迟绑 */, null, null);
            if (sDisplay == null) {
                TLog.e(TAG, "createVirtualDisplay null (w=" + sW + " h=" + sH + ")");
                return -1;
            }
            sDisplayId = sDisplay.getDisplay().getDisplayId();
            TLog.i(TAG, "virtual display created (projection): id=" + sDisplayId
                    + " " + sW + "x" + sH + " dpi=" + sDpi);
            return sDisplayId;
        } catch (Throwable t) {
            TLog.e(TAG, "ensure fail", t);
            release();
            return -1;
        }
    }

    /** 2.2.3：MediaProjection 授权回执（MainActivity.onActivityResult 转发）。 */
    public static void onProjectionResult(Context ctx, int resultCode, Intent data) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            sPendingResultCode = resultCode;
            sPendingResult = data;
            TLog.i(TAG, "projection authorized (token saved)");
        } else {
            sPendingResult = null;
            TLog.w(TAG, "projection denied by user");
        }
    }

    /** 是否已授权（引导 UI 用）。 */
    public static boolean projectionReady() {
        return sProjection != null || sPendingResult != null;
    }

    /** TextureView 的 Surface 可用了 → 绑到虚拟屏（画面开始流动）。 */
    public static void bindSurface(TextureView tv) {
        if (sDisplay == null || tv == null) {
            return;
        }
        try {
            Surface s = new Surface(tv.getSurfaceTexture());
            sDisplay.setSurface(s);
            TLog.i(TAG, "surface bound to display " + sDisplayId);
        } catch (Throwable t) {
            TLog.e(TAG, "bindSurface fail", t);
        }
    }

    /**
     * 收起后的省电策略：surface 解绑（虚拟屏没有消费者时 guest 仍 RESUMED，
     * 但 SurfaceFlinger 不再合成，编码/渲染负担最小）。
     */
    public static void unbindSurface() {
        if (sDisplay == null) {
            return;
        }
        try {
            sDisplay.setSurface(null);
            TLog.i(TAG, "surface unbound");
        } catch (Throwable t) {
            TLog.w(TAG, "unbindSurface fail: " + t);
        }
    }

    /** 全量释放（宿主退出/窗户功能关闭）。 */
    public static void release() {
        try {
            if (sDisplay != null) {
                sDisplay.release();
                TLog.i(TAG, "virtual display released");
            }
        } catch (Throwable ignore) {
        }
        sDisplay = null;
        sDisplayId = -1;
        // token 保留（sPendingResult 不清）：一次授权，进程存活期间可重建屏
    }

    /** 把 setLaunchDisplayId 塞进 ActivityOptions（hidden API，宿主已全量豁免）。 */
    public static android.app.ActivityOptions withDisplay(android.app.ActivityOptions opts, int displayId) {
        if (opts == null || displayId < 0) {
            return opts;
        }
        try {
            java.lang.reflect.Method m = android.app.ActivityOptions.class
                    .getMethod("setLaunchDisplayId", int.class);
            m.invoke(opts, displayId);
            return opts;
        } catch (Throwable t) {
            TLog.w(TAG, "setLaunchDisplayId unavailable: " + t);
            return opts;
        }
    }

    /** guest 进程内调用同样有效（hidden 豁免是全局的）。 */
    public static boolean displayApiOk() {
        try {
            android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
