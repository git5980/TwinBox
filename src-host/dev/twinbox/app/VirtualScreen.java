package dev.twinbox.app;

import android.content.Context;
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
     * 创建（或复用）虚拟屏。分辨率 = 物理屏真实像素（1:1，触摸坐标零变换）。
     * Surface 可以稍后绑（TextureView available 回调时 setSurface）。
     * 2.2.2：PUBLIC 屏——OWN_CONTENT_ONLY 是 private 屏，ColorOS 拒绝
     * 其他进程（引擎 :x）launch 到它（真机日志实锤 SecurityException:
     * "Permission Denial ... with launchDisplayId=8"）。PUBLIC 是 Cast/
     * 双屏应用的公开机制，允许三方 launch。
     *
     * @return displayId；-1 = 失败（displayManager 不给建，几乎不可能）
     */
    public static int ensure(Context ctx) {
        if (sDisplayId >= 0) {
            return sDisplayId;
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
            sDisplay = dm.createVirtualDisplay(
                    NAME + "@" + android.os.Process.myUid(),
                    sW, sH, sDpi, null /* surface 延迟绑 */,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (sDisplay == null) {
                TLog.e(TAG, "createVirtualDisplay null (w=" + sW + " h=" + sH + ")");
                return -1;
            }
            sDisplayId = sDisplay.getDisplay().getDisplayId();
            TLog.i(TAG, "virtual display created: id=" + sDisplayId
                    + " " + sW + "x" + sH + " dpi=" + sDpi);
            return sDisplayId;
        } catch (Throwable t) {
            TLog.e(TAG, "ensure fail", t);
            release();
            return -1;
        }
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
