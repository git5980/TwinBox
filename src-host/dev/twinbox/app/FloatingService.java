package dev.twinbox.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮抽屉 + 全屏窗户（VMOS 式）：贴右缘把手 + 侧面板 + 应用拉出/收回。
 *
 * TwinBox 2.1.72 窗户交互（用户定义）：
 *  - 容器应用在后台时，拖把手从右到左 → 后台应用像窗户一样被拉出（全屏
 *    跟手：真 task 瞬时调前台，深色半透明窗帘盖着，窗帘=窗户未拉开的部分）；
 *  - 拉出过半松手 → 全屏显示（窗户全开），把手吸附左缘；
 *  - 把手往回拖（左→右）→ 窗帘从左侧盖回（应用逐渐被收起），
 *    过半松手 → 应用收回后台（宿主回前台，窗帘盖着无感切换），把手回右缘；
 *  - 全开态点按把手 = 一键收回。
 *
 * TwinBox 2.1.63 抽屉（保留，无运行中应用时拖动=面板）：
 *  - 无 guest 运行时：横向拖把手=面板拉出/收回，点按=toggle；
 *  - 纵向拖：整抽屉挪位；展开态点外部：收回。
 *  - 有 guest 运行时：拖动一律=窗户，面板靠点按展开。
 *
 * 历史包袱（2.0.8 修复清单）仍在生效：DOWN 必须 return true（否则整棵
 * 手势链断）；空容器不 removeView 未 attach 的 view（safeRemove 统一出口）。
 */
public class FloatingService extends Service {

    private static volatile boolean sRunning = false;

    public static boolean isRunning() {
        return sRunning;
    }

    private WindowManager mWm;
    private View mDrawer;
    private View mHandle;
    private WindowManager.LayoutParams mDrawerLp;
    private ValueAnimator mSnapAnim;

    /** 抽屉容器总宽（把手+面板），measure 后回填 */
    private int mContainerW;
    /** 2.2.1：面板 VISIBLE 时的容器宽（面板 GONE 后容器只剩球，展开位要用旧值） */
    private int mOpenContainerW;
    /** 把手宽（贴边态容器留在屏内的部分） */
    private int mHandleW;
    private int mScreenW;
    private int mScreenH;

    private float mDownX, mDownY;
    private int mStartX, mStartY;
    private boolean mDragging;

    // ---------------- TwinBox 2.1.72：全屏窗户（VMOS 式把手拉出/收回） ----------------
    // 用户定义的交互：应用退后台后球=把手；把手从右拖到左，后台应用像窗户
    // 一样被拖出来（全屏、跟手）；拖回去像窗户收回。拖动全程底下是真 task
    // （moveTaskToFront 瞬时调来，被窗帘半透明盖着），窗帘跟手平移——
    // "live 窗户"效果，不依赖 freeform/截图/特权。
    private android.widget.FrameLayout mCurtain;   // 全屏窗帘（深色半透明）
    private android.widget.TextView mCurtainTitle;
    private android.widget.TextView mCurtainHint;
    private WindowManager.LayoutParams mCurtainLp;
    private boolean mCurtainOn;
    private boolean mWinDrag;        // 窗户拖动中
    private boolean mWinPullOut;     // true=拖出（右→左）false=拖回（左→右）
    private boolean mGuestOpen;      // 窗户全开态（应用全屏，球贴左缘）
    private int mPullTaskId = -1;    // 窗户目标 task（系统侧）
    private String mPullLabel = "";
    private String mPullPkg = "";     // 窗户目标包名（task 死了重启用）
    private int mBeneathTaskId = -1;  // 窗户底下的 task（拉出前前台，收回时恢复它）
    private List<com.lody.virtual.remote.AppTaskInfo> mRunCache;
    /** 包名 → 应用名缓存（fillPanel 维护，refreshRunCache 取 label 用） */
    private final java.util.Map<String, String> mLabelByPkg = new java.util.HashMap<String, String>();
    /** 运行列表缓存时间戳（refreshRunCache 节流用，2.1.74 合并自 DeepSeek） */
    private long mRunCacheAt;
    /** 连续手势内刷新运行列表的最小间隔（ms）——避免主线程反复打引擎 IPC */
    private static final long RUN_CACHE_TTL_MS = 600L;

    // ------------- TwinBox 2.2.0：窗户 v2（VMOS 架构：虚拟屏 + TextureView 悬浮窗） -------------
    private android.widget.FrameLayout mWinV2;        // 全屏浮窗容器（右缘贴边，宽度=拉开量）
    private android.view.TextureView mWinTexture;      // guest 画面（虚拟屏 Surface）
    private WindowManager.LayoutParams mWinV2Lp;
    private boolean mWinV2On;
    private boolean mV2Ready;                          // 虚拟屏+guest 已就位（v2 生效中）

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sRunning = true;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        mScreenW = dm.widthPixels;
        mScreenH = dm.heightPixels;
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Throwable t) {
            // Android 12+ 后台起 FGS / 14+ specialUse 缺权限 → 退化普通服务
            TLog.e("Float", "startForeground fail, degrade to background", t);
        }
        createDrawer();
    }

    private static final int NOTIFY_ID = 9;

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("float", "悬浮球",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "float")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("双生盒悬浮抽屉")
                .setContentText("拖把手：像窗户一样拉出/收回容器应用；点按：列表")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------------------------------------------------------------- 抽屉

    private void createDrawer() {
        mDrawer = LayoutInflater.from(this).inflate(R.layout.floating_drawer, null, false);
        mHandle = mDrawer.findViewById(R.id.drawer_handle);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        mDrawerLp = lp;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = mScreenW; // measure 前先放屏外，layout 回调里再贴边

        // 容器 measure 完成后贴边（拿真实宽度算贴边位）
        mDrawer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int w = right - left;
                int handleW = mHandle.getWidth();
                if (w > 0 && handleW > 0 && (w != mContainerW || handleW != mHandleW)) {
                    mContainerW = w;
                    mHandleW = handleW;
                    View panel = mDrawer.findViewById(R.id.drawer_panel);
                    if (panel != null && panel.getVisibility() == View.VISIBLE) {
                        mOpenContainerW = w;   // 2.2.1：记住面板展开时的容器宽
                    }
                    if (mDrawerLp.x >= mScreenW - 4) {
                        // 首次：贴边（只露球）
                        mDrawerLp.x = edgeX();
                        applyLayout();
                    }
                }
            }
        });

        fillPanel();
        // TwinBox 2.1.72：启动即预取窗户目标（不依赖用户先开过面板）。
        // fillPanel 里已经 force 刷新过一次，这里只是保险，直接沿用缓存。
        refreshRunCache(false);

        mHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });
        // 展开态点容器外 → 收回（OUTSIDE 只在 DOWN 时派发给 watch 方）
        mDrawer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    collapsePanel();
                    return true;
                }
                return false;
            }
        });

        // 2.2.1：球形态起步（面板 GONE，点击才展开——VMOS 同款）
        View panel0 = mDrawer.findViewById(R.id.drawer_panel);
        if (panel0 != null) {
            panel0.setVisibility(View.GONE);
        }
        try {
            mWm.addView(mDrawer, lp);
        } catch (Throwable t) {
            TLog.e("Float", "addView drawer fail", t);
        }
    }

    /** 贴边位：容器只露球（面板 GONE 态容器宽=球宽） */
    private int edgeX() {
        return mScreenW - mHandleW;
    }

    /** 展开位：面板全露，球贴着面板左缘 */
    private int openX() {
        return mScreenW - Math.max(mOpenContainerW, mContainerW);
    }

    /** 2.2.1：面板展开（点击球）。面板 VISIBLE + 刷新内容 + 滑到展开位。 */
    private void expandPanel() {
        View panel = mDrawer.findViewById(R.id.drawer_panel);
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
        }
        fillPanel();
        // 等 measure（容器宽更新 onLayoutChange 记 mOpenContainerW）后再滑
        mDrawer.post(new Runnable() {
            @Override
            public void run() {
                snapTo(openX());
            }
        });
    }

    /** 2.2.1：面板收起（再点球 / 点外部）。滑回贴边位，动画后 GONE。 */
    private void collapsePanel() {
        snapTo(edgeX());
        mDrawer.postDelayed(new Runnable() {
            @Override
            public void run() {
                View panel = mDrawer.findViewById(R.id.drawer_panel);
                if (panel != null && !mGuestOpen && !mWinDrag) {
                    panel.setVisibility(View.GONE);
                }
            }
        }, 240);
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSnap();
                // 手势开始即刷新运行列表（2.1.74：带节流，连续手势不打爆引擎 IPC）
                refreshRunCache(false);
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mStartX = mDrawerLp.x;
                mStartY = mDrawerLp.y;
                mDragging = false;
                mWinDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - mDownX;
                float dy = event.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                    mDragging = true;
                }
                if (!mDragging) {
                    return true;
                }
                // TwinBox 2.2.1（VMOS 式）：球在右半屏 + 有容器应用在跑 → 拖动=拉窗户；
                // 其余情况（球在左半屏/无应用）→ 拖动=自由移动球（松手贴边）。
                boolean hasGuest = mPullTaskId >= 0
                        && mRunCache != null && !mRunCache.isEmpty();
                boolean rightSide = mStartX > mScreenW / 2;
                if (hasGuest && rightSide) {
                    if (!mWinDrag) {
                        mWinPullOut = true;
                        beginWinDrag();
                    }
                    // 横向：球（=窗户边缘）跟手，范围 [0, edgeX]
                    int nx = (int) Math.max(0, Math.min(mScreenW - 8, mStartX + dx));
                    mDrawerLp.x = nx;
                    applyLayout();
                    // v2：浮窗展开量跟手（右对齐）；v1：窗帘跟手
                    if (mV2Ready && mWinV2On) {
                        setWinV2Width(mScreenW - nx);
                    } else {
                        setCurtainPos(nx);
                    }
                    // 球拖动反馈（VMOS：拖动中半透明）
                    mDrawer.setAlpha(0.55f);
                    return true;
                }
                // 球自由移动（VMOS 同款）：先收面板（GONE），再跟手
                View panel = mDrawer.findViewById(R.id.drawer_panel);
                if (panel != null && panel.getVisibility() == View.VISIBLE) {
                    panel.setVisibility(View.GONE);
                }
                int px = (int) (mStartX + dx);
                mDrawerLp.x = Math.max(0, Math.min(edgeX(), px));
                int handleH = Math.max(1, mHandle.getHeight());
                int ny = (int) (mStartY + dy);
                mDrawerLp.y = Math.max(0, Math.min(mScreenH - handleH, ny));
                applyLayout();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                boolean moved = Math.abs(event.getRawX() - mDownX) > 12
                        || Math.abs(event.getRawY() - mDownY) > 12;
                if (mWinDrag) {
                    // 窗户松手：拉出过半=全开；否则收回
                    mWinDrag = false;
                    if (mDrawerLp.x < mScreenW / 2) {
                        winOpen();
                    } else {
                        winClose();
                    }
                    return true;
                }
                mDrawer.setAlpha(1f);
                if (!moved) {
                    if (mGuestOpen) {
                        // 全开态点球：收回应用（一步到位，最顺手）
                        winClose();
                        return true;
                    }
                    // 点按球 = 菜单（VMOS 同款）：面板展开 ↔ 收起
                    View panel = mDrawer.findViewById(R.id.drawer_panel);
                    boolean showing = panel != null && panel.getVisibility() == View.VISIBLE
                            && mDrawerLp.x < (edgeX() + openX()) / 2;
                    if (showing) {
                        collapsePanel();
                    } else {
                        expandPanel();
                    }
                } else {
                    // 球拖动松手：就近贴边（VMOS 同款）
                    snapTo(mDrawerLp.x < mScreenW / 2 ? 0 : edgeX());
                }
                mDragging = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mWinDrag) {
                    mWinDrag = false;
                    if (mDrawerLp.x < mScreenW / 2) {
                        winOpen();
                    } else {
                        winClose();
                    }
                    return true;
                }
                // 取消时球就近贴边
                snapTo(mDrawerLp.x < mScreenW / 2 ? 0 : edgeX());
                mDragging = false;
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 全屏窗户

    /** 窗户拖动开始：v2（虚拟屏浮窗）优先，v1（窗帘+task切换）兜底。 */
    private void beginWinDrag() {
        mWinDrag = true;
        // 面板收起：窗户模式容器只剩把手（面板 GONE），不挡底下的真应用
        View panel = mDrawer.findViewById(R.id.drawer_panel);
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        // 把手置顶（浮窗后 add 会盖住它，拖动就断了）
        try {
            mWm.removeView(mDrawer);
            mWm.addView(mDrawer, mDrawerLp);
        } catch (Throwable ignore) {
        }
        if (mWinPullOut) {
            // 拉出：v2 先试（虚拟屏 + TextureView 浮窗）
            if (!startWindowV2()) {
                // 降级 v1：记住窗户底下的 task + robust 拉真 task 到前台
                mBeneathTaskId = captureBeneathTask();
                bringGuestRobust();
                showCurtain();
            } else {
                // v2 起飞：先窗帘垫场（TextureView 出帧前是透明的），
                // 出帧后 verifyV2Async 会撤窗帘、浮窗亮起
                showCurtain();
            }
        } else {
            // 拖回：v1 才需要窗帘（v2 底下本来就是原界面）
            if (!mV2Ready) {
                showCurtain();
            }
        }
    }

    /**
     * TwinBox 2.2.0：窗户 v2 启动（VMOS 架构复用）。
     * 虚拟屏 + guest launch 到虚拟屏 + TextureView 全屏浮窗（贴右缘，宽度=拉开量）。
     * 首帧到达（getTimestamp 变化）前浮窗透明 → 窗帘垫场；到达后 v2 接管。
     */
    private boolean startWindowV2() {
        try {
            if (mPullPkg == null || mPullPkg.length() == 0) {
                return false;
            }
            int did = VirtualScreen.ensure(this);
            if (did < 0) {
                return false;
            }
            if (!mV2Ready) {
                buildWinV2();
                // 验证位：240px（1px 时 Surface 过小，验证环境不真实）
                setWinV2Width(240);
                migrateAndLaunch(did);
            } else {
                setWinV2Width(mScreenW - mDrawerLp.x);
            }
            return true;
        } catch (Throwable t) {
            TLog.e("Float", "startWindowV2 fail", t);
            return false;
        }
    }

    /**
     * 2.2.1：v2 冷迁移 + 启动（后台线程）。
     * 真机日志实锤：AMS 对已存在的 task 忽略 launchDisplayId（Task 始终
     * d=0）。guest 已在主屏跑时必须先 finishAndRemoveTask（同 uid appTask
     * 可 finish 自己的），再冷 launchToDisplay——新 task born on display N。
     * 代价：activity 重建（抖音回首页）。v2 语义边界，先保画面通路。
     */
    private void migrateAndLaunch(final int displayId) {
        final String pkg = mPullPkg;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean killed = false;
                    try {
                        android.app.ActivityManager am =
                                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                        for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                            android.app.ActivityManager.RecentTaskInfo ri = t.getTaskInfo();
                            if (ri != null && ri.taskId == mPullTaskId) {
                                t.finishAndRemoveTask();
                                killed = true;
                                break;
                            }
                        }
                    } catch (Throwable tk) {
                        TLog.w("Float", "v2 migrate finish fail: " + tk);
                    }
                    if (killed) {
                        TLog.i("Float", "v2: main-screen task " + mPullTaskId
                                + " finished → cold relaunch on display " + displayId);
                        android.os.SystemClock.sleep(300);
                    }
                    int res = VBox.launchToDisplay(FloatingService.this, pkg, 0, displayId);
                    if (res != 0) {
                        TLog.w("Float", "v2: launchToDisplay res=" + res);
                    }
                    verifyV2Async(displayId);
                } catch (Throwable t) {
                    TLog.e("Float", "migrateAndLaunch fail", t);
                }
            }
        }, "tb-v2-mig").start();
    }

    /**
     * 2.2.1：启动区点按 = 直接进窗户（VMOS 语义：应用活在虚拟屏）。
     * 冷启动到虚拟屏 + 浮窗全开 + 验证；失败自动降级普通全屏启动。
     */
    public void launchInWindow(final String pkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int did = VirtualScreen.ensure(FloatingService.this);
                    if (did < 0) {
                        VBox.launch(FloatingService.this, pkg, 0);
                        return;
                    }
                    int res = VBox.launchToDisplay(FloatingService.this, pkg, 0, did);
                    if (res != 0) {
                        VBox.launch(FloatingService.this, pkg, 0);
                        return;
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(new Runnable() {
                                @Override
                                public void run() {
                                    mGuestOpen = true;
                                    buildWinV2();
                                    animWinV2Width(mScreenW);
                                    ballAnimTo(0);
                                    verifyV2Async(did);
                                }
                            });
                } catch (Throwable t) {
                    TLog.e("Float", "launchInWindow fail", t);
                }
            }
        }, "tb-v2-liw").start();
    }

    /** 建浮窗：TYPE_APPLICATION_OVERLAY，右缘贴边，TextureView 铺满。 */
    private void buildWinV2() {
        if (mWinV2 != null) {
            return;
        }
        mWinV2 = new android.widget.FrameLayout(this);
        mWinV2.setBackgroundColor(0xFF06080D);
        mWinTexture = new android.view.TextureView(this);
        // 2.2.0：触摸直派通道（AIDL）下一版接入；本版点击给明确提示，
        // 收回/拖动/全开已可用。把手在浮窗之上（beginWinDrag 置顶）。
        mWinTexture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(FloatingService.this,
                        "浮窗模式：拖把手收回窗户。直接操作画面将在下版支持",
                        Toast.LENGTH_SHORT).show();
            }
        });
        mWinTexture.setClickable(true);
        mWinV2.addView(mWinTexture, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        mWinTexture.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                VirtualScreen.bindSurface(mWinTexture);
                TLog.i("Float", "v2: texture available " + w + "x" + h);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
            }
        });
        mWinV2Lp = new WindowManager.LayoutParams(
                1, mScreenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        mWinV2Lp.gravity = Gravity.TOP | Gravity.START;
        mWinV2Lp.x = 0;
        mWinV2Lp.y = 0;
        try {
            mWm.addView(mWinV2, mWinV2Lp);
            mWinV2On = true;
        } catch (Throwable t) {
            TLog.e("Float", "v2 addView fail", t);
            mWinV2 = null;
        }
    }

    /** v2 浮窗宽度（=窗户拉开量，右对齐展开）。 */
    private void setWinV2Width(int width) {
        if (!mWinV2On || mWinV2Lp == null) {
            return;
        }
        int w = Math.max(1, Math.min(mScreenW, width));
        if (mWinV2Lp.width == w && mWinV2Lp.x == mScreenW - w) {
            return;
        }
        mWinV2Lp.width = w;
        mWinV2Lp.x = mScreenW - w;   // 右对齐
        try {
            mWm.updateViewLayout(mWinV2, mWinV2Lp);
        } catch (Throwable t) {
            TLog.w("Float", "setWinV2Width fail: " + t);
        }
    }

    /**
     * 首帧验证：3.5s 内 TextureView 有内容（SurfaceTexture timestamp 前进）
     * → v2 生效（撤窗帘，浮窗接管）；无帧 → v2 死（launch 没到虚拟屏 /
     * OEM 拒 setLaunchDisplayId）→ 撤浮窗，降级 v1（窗帘 + task 切换）。
     */
    private void verifyV2Async(final int displayId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final long t0 = textureTimestamp();
                    long t = t0;
                    for (int i = 0; i < 35; i++) {
                        android.os.SystemClock.sleep(100);
                        t = textureTimestamp();
                        if (t > t0 && t > 0) {
                            break;
                        }
                    }
                    final boolean live = t > 0 && t > t0;
                    TLog.i("Float", "v2 verify: display=" + displayId
                            + " t0=" + t0 + " t=" + t + " live=" + live);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(new Runnable() {
                                @Override
                                public void run() {
                                    if (live) {
                                        mV2Ready = true;
                                        // 窗帘功成身退：v2 画面接管
                                        safeRemoveCurtain();
                                        setWinV2Width(mScreenW - mDrawerLp.x);
                                        Toast.makeText(FloatingService.this,
                                                "已进入浮窗模式（画面来自虚拟屏）",
                                                Toast.LENGTH_SHORT).show();
                                    } else {
                                        destroyWinV2();
                                        // v1 接管：窗帘在，task 拉前
                                        mBeneathTaskId = captureBeneathTask();
                                        bringGuestRobust();
                                    }
                                }
                            });
                } catch (Throwable t) {
                    TLog.w("Float", "verifyV2 fail: " + t);
                }
            }
        }, "tb-v2-verify").start();
    }

    private long textureTimestamp() {
        try {
            if (mWinTexture != null && mWinTexture.getSurfaceTexture() != null) {
                return mWinTexture.getSurfaceTexture().getTimestamp();
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /** v2 撤收（验证失败 / 用户关窗户功能）。guest task 已发去虚拟屏，
     *  验证失败说明它没上去（还在主屏 task 或死了），v1 的 bringGuestRobust 会拉它。 */
    private void destroyWinV2() {
        if (mWinV2On && mWinV2 != null) {
            try {
                mWm.removeView(mWinV2);
            } catch (Throwable ignore) {
            }
        }
        mWinV2 = null;
        mWinTexture = null;
        mWinV2On = false;
        mV2Ready = false;
        TLog.i("Float", "v2 destroyed (fallback or close)");
    }

    /**
     * 2.1.73：拉出前记一下当前前台 task（= 窗户底下的界面）。
     * getRunningTasks 只能看到自己 uid 的 task（guest 以宿主 stub 名义注册，
     * 同 uid 可见）——拿得到宿主自己的；拿不到（别的 app/桌面）返回 -1，
     * 收回时走 HOME 兜底。
     * 2.1.74（DeepSeek 知识）：guest 的 stub task 也是 dev.twinbox.app 包名
     * （StubActivity 跑在宿主进程）——按包名过滤会撞名，但这里按 taskId 恢复
     * 天然免疫：窗户底下是另一个 guest 就恢复那个 guest（用户当时看的界面）。
     */
    private int captureBeneathTask() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningTaskInfo> l = am.getRunningTasks(1);
            if (l != null && !l.isEmpty() && l.get(0).taskId != mPullTaskId) {
                return l.get(0).taskId;
            }
        } catch (Throwable t) {
            TLog.w("Float", "captureBeneath fail: " + t);
        }
        return -1;
    }

    /**
     * 2.1.73：拉出健壮化（修「透明界面」）。
     * 根因：moveTaskToFront 对被系统冻结（o-stop）/已死的 task 无效——task
     * 拉回来了但进程不恢复 → 透明。策略：
     *  1) 系统侧 task 已不存在 → 直接 VBox.launch 重启（窗帘盖着启动过程）；
     *  2) move 后 2.4s 内没到前台 → 再补一次 launch（冷恢复慢/被冻结）。
     * 后台线程跑，不卡手势。
     */
    private void bringGuestRobust() {
        final int taskId = mPullTaskId;
        final String pkg = mPullPkg;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.ActivityManager am =
                            (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    boolean alive = false;
                    try {
                        for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                            android.app.ActivityManager.RecentTaskInfo ri = t.getTaskInfo();
                            if (ri != null && ri.taskId == taskId) {
                                alive = true;
                                break;
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                    if (alive) {
                        try {
                            am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION);
                            TLog.i("Float", "win: moveTaskToFront " + taskId);
                        } catch (Throwable tMove) {
                            // 2.2.1：ColorOS 对 stub task 的 moveTaskToFront 直接
                            // 抛 NPE（真机日志实锤）——不能让它炸掉整个恢复线程，
                            // 下面确认循环 + relaunch 兜底会接着处理。
                            TLog.w("Float", "moveTaskToFront failed (ColorOS?): "
                                    + tMove + " → fallback launch");
                            if (pkg != null && pkg.length() > 0) {
                                VBox.launch(FloatingService.this, pkg, 0);
                                return;
                            }
                        }
                    } else if (pkg != null && pkg.length() > 0) {
                        TLog.w("Float", "win: task " + taskId + " gone → relaunch " + pkg);
                        VBox.launch(FloatingService.this, pkg, 0);
                        return; // launch 链自带恢复
                    }
                    // 确认循环：最多 2.4s
                    for (int i = 0; i < 12 && !isGuestFront(); i++) {
                        android.os.SystemClock.sleep(200);
                    }
                    if (!isGuestFront() && pkg != null && pkg.length() > 0) {
                        TLog.w("Float", "win: not front after 2.4s → relaunch " + pkg);
                        VBox.launch(FloatingService.this, pkg, 0);
                    }
                } catch (final Throwable t) {
                    TLog.e("Float", "bringGuestRobust fail", t);
                }
            }
        }, "tb-win").start();
    }

    private void showCurtain() {
        if (mCurtain == null) {
            mCurtain = new android.widget.FrameLayout(this);
            mCurtain.setBackgroundColor(0xF20E1117);
            mCurtainTitle = new TextView(this);
            mCurtainTitle.setTextColor(0xFFC8D3F0);
            mCurtainTitle.setTextSize(22);
            android.widget.FrameLayout.LayoutParams lp1 =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, 17 /*CENTER*/);
            mCurtain.addView(mCurtainTitle, lp1);
            mCurtainHint = new TextView(this);
            mCurtainHint.setTextColor(0xFF7A86A8);
            mCurtainHint.setTextSize(13);
            android.widget.FrameLayout.LayoutParams lp2 =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, 49 /*TOP|CH*/);
            lp2.topMargin = (int) (48 * getResources().getDisplayMetrics().density);
            mCurtain.addView(mCurtainHint, lp2);
            mCurtainLp = new WindowManager.LayoutParams(
                    mScreenW, mScreenH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            mCurtainLp.gravity = Gravity.TOP | Gravity.LEFT;
            mCurtainLp.x = 0;
            mCurtainLp.y = 0;
        }
        mCurtainTitle.setText(mPullLabel == null || mPullLabel.isEmpty() ? "容器应用" : mPullLabel);
        mCurtainHint.setText(mWinPullOut ? "‹ 继续向左拖出 · 松手全屏打开" : "继续向右拖 · 松手收起 ›");
        // 2.1.73：先定位再上屏——修「addView 瞬间 translationX=0 全屏黑一帧」闪现
        mCurtain.setTranslationX(mWinPullOut ? mDrawerLp.x : mDrawerLp.x - mScreenW);
        if (!mCurtainOn) {
            try {
                mWm.addView(mCurtain, mCurtainLp);
                mCurtainOn = true;
            } catch (Throwable t) {
                TLog.e("Float", "curtain addView fail", t);
            }
        }
        // 初始位（当前球位）
        setCurtainPos(mWinPullOut ? mDrawerLp.x : mDrawerLp.x - mScreenW);
    }

    private void setCurtainPos(float tx) {
        if (mCurtainOn && mCurtain != null) {
            mCurtain.setTranslationX(tx);
        }
    }

    /** 窗帘滑出屏幕后移除。 */
    private void dropCurtain(float endTx) {
        if (!mCurtainOn || mCurtain == null) {
            return;
        }
        try {
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator
                    .ofFloat(mCurtain, "translationX", mCurtain.getTranslationX(), endTx);
            a.setDuration(220);
            a.setInterpolator(new DecelerateInterpolator());
            a.addListener(new android.animation.Animator.AnimatorListener() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    safeRemoveCurtain();
                }

                @Override
                public void onAnimationStart(android.animation.Animator animation) {
                }

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                }

                @Override
                public void onAnimationRepeat(android.animation.Animator animation) {
                }
            });
            a.start();
        } catch (Throwable t) {
            safeRemoveCurtain();
        }
    }

    private void safeRemoveCurtain() {
        if (mCurtainOn && mCurtain != null) {
            try {
                if (mCurtain.isAttachedToWindow()) {
                    mWm.removeView(mCurtain);
                }
            } catch (Throwable t) {
                TLog.w("Float", "remove curtain fail: " + t);
            }
        }
        mCurtainOn = false;
    }

    /** 窗户全开：v2=浮窗铺满（主屏 task 不动）；v1=应用真全屏+球贴左缘。 */
    private void winOpen() {
        mGuestOpen = true;
        if (mV2Ready && mWinV2On) {
            // v2：浮窗一步铺满（可加动画），底下界面原样
            animWinV2Width(mScreenW);
            ballAnimTo(0);
            return;
        }
        ballAnimTo(0);
        dropCurtain(mWinPullOut ? mScreenW : -mScreenW);
        // 2.1.74（DeepSeek 合并）：全开前验证 guest 真到前台了。bringGuestRobust
        // 拉出时就开跑（含 2.4s 确认循环），这里只兜最坏情况：验证失败再增援一次，
        // 仍失败 → abort（球回右缘、面板恢复、窗帘撤掉）——绝不出现
        // 「球贴左缘+窗帘全开+底下没应用」的死态。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.SystemClock.sleep(300);
                    if (isGuestFront()) {
                        return;
                    }
                    bringGuestRobust();   // 增援（内部有验活/重启/确认循环）
                    android.os.SystemClock.sleep(1200);
                    if (!isGuestFront()) {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(new Runnable() {
                                    @Override
                                    public void run() {
                                        abortWinDrag("guest not front after retry");
                                    }
                                });
                    }
                } catch (Throwable ignore) {
                }
            }
        }, "tb-win-verify").start();
    }

    /** v2 浮窗宽度动画（收/展开）。 */
    private void animWinV2Width(final int targetW) {
        if (!mWinV2On || mWinV2Lp == null) {
            return;
        }
        cancelSnap();
        final int from = mWinV2Lp.width;
        mSnapAnim = ValueAnimator.ofInt(from, targetW);
        mSnapAnim.setDuration(240);
        mSnapAnim.setInterpolator(new DecelerateInterpolator());
        mSnapAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setWinV2Width((Integer) animation.getAnimatedValue());
            }
        });
        mSnapAnim.start();
    }

    /** 窗户异常中止：v2=撤浮窗；球回右缘、面板恢复、窗帘撤掉。 */
    private void abortWinDrag(String why) {
        if (!mGuestOpen) {
            return;
        }
        TLog.w("Float", "win abort: " + why);
        mGuestOpen = false;
        if (mV2Ready) {
            destroyWinV2();
        }
        // 窗户底下还是原界面（guest 没上来过），直接撤
        ballAnimTo(edgeX());
        safeRemoveCurtain();
        View panel = mDrawer.findViewById(R.id.drawer_panel);
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
        }
    }

    /** 窗户收回：v2=浮窗收起（guest 留虚拟屏跑，底下界面原样不动）；
     *  v1=应用送后台（恢复窗户底下的 task）。 */
    private void winClose() {
        mGuestOpen = false;
        if (mV2Ready && mWinV2On) {
            // v2：浮窗收到右缘（宽度→1 挂屏外），guest 继续在虚拟屏跑
            animWinV2Width(1);
            ballAnimTo(edgeX());
            // 2.2.1：面板保持 GONE（关窗后菜单收着，点球再开——VMOS 同款）
            TLog.i("Float", "v2: window closed, guest stays on virtual display");
            return;
        }
        // 2.1.73：恢复「窗户底下的界面」（拉出前记的 task / HOME 兜底），
        // 绝不拉宿主主界面——用户反馈：收回后见到的应是原来的界面。
        restoreBeneath();
        ballAnimTo(edgeX());
        dropCurtain(mWinPullOut ? mScreenW : -mScreenW);
        // 2.2.1：面板保持 GONE（点球再开菜单）
    }

    /**
     * 2.1.73：收回时恢复窗户底下的界面。
     * - 拉出前记到的 task（宿主 uid 内可见）→ move 回来；
     * - 记不到（窗户下面是桌面/别的 app）→ 发 HOME 回桌面，
     *   launcher 起来把 guest 挤后台。窗帘盖着整个切换，无感。
     */
    private void restoreBeneath() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mBeneathTaskId >= 0) {
                        android.app.ActivityManager am =
                                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                        boolean alive = false;
                        try {
                            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                                android.app.ActivityManager.RecentTaskInfo ri = t.getTaskInfo();
                                if (ri != null && ri.taskId == mBeneathTaskId) {
                                    alive = true;
                                    break;
                                }
                            }
                        } catch (Throwable ignore) {
                        }
                        if (alive) {
                            am.moveTaskToFront(mBeneathTaskId,
                                    android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION);
                            TLog.i("Float", "win: beneath task " + mBeneathTaskId + " restored");
                            return;
                        }
                    }
                    // HOME 兜底：回桌面（不拉宿主主界面）
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(home);
                    TLog.i("Float", "win: HOME (beneath restore fallback)");
                } catch (Throwable t) {
                    TLog.w("Float", "restoreBeneath fail: " + t);
                }
            }
        }, "tb-win-close").start();
    }

    /**
     * 窗户目标预取/刷新（2.1.74 合并自 DeepSeek：带节流）。
     * 返回本次拿到的运行列表，调用方直接复用，不要再自己调 VBox.runningTasks()
     * ——那会多打一次引擎 IPC。
     *
     * @param force true=强制刷新；false=按节流间隔（连续拖动手势不打爆引擎）
     */
    private List<com.lody.virtual.remote.AppTaskInfo> refreshRunCache(boolean force) {
        List<com.lody.virtual.remote.AppTaskInfo> r = mRunCache;
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && r != null && now - mRunCacheAt < RUN_CACHE_TTL_MS) {
            return r;   // 节流命中：沿用上次结果
        }
        try {
            r = VBox.runningTasks();
            mRunCacheAt = now;
            mRunCache = r;   // 2.1.75：合并时漏了这行——mRunCache 恒 null → hasGuest 恒 false → 窗户永不触发
            if (!r.isEmpty()) {
                // 当前目标还在列表里就不换（窗户目标稳定）
                boolean keep = false;
                for (com.lody.virtual.remote.AppTaskInfo ti : r) {
                    if (ti.taskId == mPullTaskId) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    com.lody.virtual.remote.AppTaskInfo t0 = r.get(0);
                    mPullTaskId = t0.taskId;
                    String pkg = t0.baseIntent != null && t0.baseIntent.getComponent() != null
                            ? t0.baseIntent.getComponent().getPackageName()
                            : (t0.topActivity != null ? t0.topActivity.getPackageName() : "?");
                    mPullPkg = pkg == null ? "" : pkg;
                    String label = mLabelByPkg != null ? mLabelByPkg.get(pkg) : null;
                    mPullLabel = label != null ? label : pkg;
                }
            } else {
                mPullTaskId = -1;
            }
        } catch (Throwable t) {
            TLog.w("Float", "refreshRunCache fail: " + t);
        }
        if (r == null) {
            r = new ArrayList<com.lody.virtual.remote.AppTaskInfo>();
        }
        return r;
    }

    /** 球位动画（不触发 fillPanel）。 */
    private void ballAnimTo(final int targetX) {
        cancelSnap();
        final int from = mDrawerLp.x;
        mSnapAnim = ValueAnimator.ofInt(from, targetX);
        mSnapAnim.setDuration(220);
        mSnapAnim.setInterpolator(new DecelerateInterpolator());
        mSnapAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mDrawerLp.x = (Integer) animation.getAnimatedValue();
                applyLayout();
            }
        });
        mSnapAnim.start();
    }

    /** 容器应用 task 是否在前台（自己 uid 的 task 对 getRunningTasks 可见）。 */
    private boolean isGuestFront() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningTaskInfo> l = am.getRunningTasks(1);
            return mPullTaskId >= 0 && l != null && !l.isEmpty()
                    && l.get(0).taskId == mPullTaskId;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 吸附动画（Decelerate，200ms）+ 箭头态切换 */
    private void snapTo(final int targetX) {
        cancelSnap();
        final int from = mDrawerLp.x;
        final boolean opening = targetX <= (edgeX() + openX()) / 2;
        mSnapAnim = ValueAnimator.ofInt(from, targetX);
        mSnapAnim.setDuration(200);
        mSnapAnim.setInterpolator(new DecelerateInterpolator());
        mSnapAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mDrawerLp.x = (Integer) animation.getAnimatedValue();
                applyLayout();
            }
        });
        mSnapAnim.addListener(new android.animation.Animator.AnimatorListener() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // TwinBox 2.1.65：每次展开时实时刷新面板——fillPanel 原先
                // 只在 onCreate 调一次，用户「先开悬浮球再启动应用」时
                // 拖出的面板里「运行中」区还是旧数据（空），小窗调出
                // 根本无处可点（真机日志实锤：全程零 moveTaskToFront）。
                if (opening) {
                    fillPanel();
                }
            }

            @Override
            public void onAnimationStart(android.animation.Animator animation) {
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
            }

            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {
            }
        });
        mSnapAnim.start();
        // 箭头：展开态 ›（向右推回）；贴边态 ‹（向左拉出）
    }

    private void cancelSnap() {
        if (mSnapAnim != null) {
            mSnapAnim.cancel();
            mSnapAnim = null;
        }
    }

    private void applyLayout() {
        try {
            mWm.updateViewLayout(mDrawer, mDrawerLp);
        } catch (Throwable t) {
            TLog.w("Float", "updateViewLayout fail: " + t);
        }
    }

    // ---------------------------------------------------------------- 面板内容

    private void fillPanel() {
        TLog.i("Float", "fillPanel: refreshing (running tasks + apps)");
        List<VBox.VAppEntry> apps;
        try {
            apps = VBox.listInstalled();
        } catch (Throwable t) {
            apps = null;
            TLog.e("Float", "listInstalled fail", t);
        }
        LinearLayout box = (LinearLayout) mDrawer.findViewById(R.id.panel_items);
        box.removeAllViews();

        // ---- TwinBox 2.1.72：运行中区 + 窗户目标缓存 ----
        // running 列表进 mRunCache（拖把手拉窗户的目标）；
        // 列表里点按 = 全屏打开该应用（用户要的是全屏，不是小窗）。
        List<com.lody.virtual.remote.AppTaskInfo> running = refreshRunCache(true);
        if (!running.isEmpty()) {
            TextView head = new TextView(this);
            head.setText("运行中 · 点按打开 · 拖把手拉出");
            head.setTextColor(0xFF8A93A8);
            head.setTextSize(12);
            head.setPadding(28, 22, 28, 6);
            box.addView(head);
            java.util.Map<String, VBox.VAppEntry> byPkg = new java.util.HashMap<String, VBox.VAppEntry>();
            if (apps != null) {
                for (VBox.VAppEntry e : apps) {
                    byPkg.put(e.packageName, e);
                    if (e.label != null) {
                        mLabelByPkg.put(e.packageName, e.label);
                    }
                }
            }
            int shownRun = 0;
            for (final com.lody.virtual.remote.AppTaskInfo ti : running) {
                if (shownRun >= 5) {
                    break;
                }
                String pkg = ti.baseIntent != null && ti.baseIntent.getComponent() != null
                        ? ti.baseIntent.getComponent().getPackageName()
                        : (ti.topActivity != null ? ti.topActivity.getPackageName() : "?");
                VBox.VAppEntry e = byPkg.get(pkg);
                String label = e != null && e.label != null ? e.label : pkg;
                TextView item = new TextView(this);
                item.setText(label);
                item.setTextColor(0xFF5B8CFF);
                item.setPadding(28, 22, 28, 22);
                item.setTextSize(14);
                // 点按 = 全屏打开（2.1.72：去掉小窗 toggle——用户要的是全屏）
                final String pkg0 = pkg;
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 2.2.1：启动即进窗户（虚拟屏），失败自动降级全屏
                        launchInWindow(pkg0);
                        snapTo(edgeX());
                    }
                });
                box.addView(item);
                shownRun++;
            }
        }

        // ---- 启动区（原功能保留）----
        if (apps == null || apps.isEmpty()) {
            if (running.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("容器内暂无应用");
                empty.setTextColor(0xFF8A93A8);
                empty.setTextSize(13);
                empty.setPadding(28, 26, 28, 26);
                box.addView(empty);
            }
            return;
        }
        TextView launchHead = new TextView(this);
        launchHead.setText("启动应用");
        launchHead.setTextColor(0xFF8A93A8);
        launchHead.setTextSize(12);
        launchHead.setPadding(28, 16, 28, 6);
        box.addView(launchHead);
        int shown = 0;
        for (final VBox.VAppEntry e : apps) {
            if (shown >= 9) {
                break;
            }
            TextView item = new TextView(this);
            item.setText(e.label == null ? e.packageName : e.label);
            item.setTextColor(Color.WHITE);
            item.setPadding(28, 26, 28, 26);
            item.setTextSize(14);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 2.2.1：启动即进窗户（虚拟屏），失败自动降级全屏
                    launchInWindow(e.packageName);
                    snapTo(edgeX());
                }
            });
            box.addView(item);
            shown++;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelSnap();
        safeRemoveCurtain();
        destroyWinV2();
        VirtualScreen.release();
        if (mDrawer != null) {
            try {
                if (mDrawer.isAttachedToWindow()) {
                    mWm.removeView(mDrawer);
                }
            } catch (Throwable t) {
                TLog.w("Float", "removeView drawer fail: " + t);
            }
            mDrawer = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        sRunning = false;
    }
}
