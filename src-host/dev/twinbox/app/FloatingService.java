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
    private TextView mHandle;
    private WindowManager.LayoutParams mDrawerLp;
    private ValueAnimator mSnapAnim;

    /** 抽屉容器总宽（把手+面板），measure 后回填 */
    private int mContainerW;
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
        mHandle = (TextView) mDrawer.findViewById(R.id.drawer_handle);

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
                    if (mDrawerLp.x >= mScreenW - 4) {
                        // 首次：贴边（只露把手）
                        mDrawerLp.x = edgeX();
                        applyLayout();
                    }
                }
            }
        });

        fillPanel();
        // TwinBox 2.1.72：启动即预取窗户目标（不依赖用户先开面板）
        refreshRunCache();

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
                    snapTo(edgeX());
                    return true;
                }
                return false;
            }
        });

        try {
            mWm.addView(mDrawer, lp);
        } catch (Throwable t) {
            TLog.e("Float", "addView drawer fail", t);
        }
    }

    /** 贴边位：容器只露把手 */
    private int edgeX() {
        return mScreenW - mHandleW;
    }

    /** 展开位：面板全露，把手贴着面板左缘 */
    private int openX() {
        return mScreenW - mContainerW;
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSnap();
                // 手势开始即刷新运行列表（数据新鲜，不依赖用户先开过面板）
                refreshRunCache();
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
                // TwinBox 2.1.72：有容器应用在跑 → 拖动=全屏窗户；
                // 没有运行中的应用 → 老行为（拖动=拉出面板）。
                boolean hasGuest = mRunCache != null && !mRunCache.isEmpty();
                if (hasGuest) {
                    if (!mWinDrag) {
                        mWinPullOut = mStartX > mScreenW / 2;
                        // 拖回但应用已不在前台（用户按过 Home）→ 按拖出处理
                        if (!mWinPullOut && !isGuestFront()) {
                            mWinPullOut = true;
                        }
                        beginWinDrag();
                    }
                    // 横向：球（=窗户边缘）跟手，范围 [0, edgeX]
                    int nx = (int) Math.max(0, Math.min(mScreenW - 8, mStartX + dx));
                    mDrawerLp.x = nx;
                    applyLayout();
                    // 窗帘跟手：拖出盖球右侧 [x, W]；拖回盖球左侧 [0, x]
                    setCurtainPos(mWinPullOut ? nx : nx - mScreenW);
                    return true;
                }
                // 纵向：整抽屉挪位（clamp 屏内）
                // 横向：贴边位 ↔ 展开位之间跟手（clamp）
                int minX = openX();
                int maxX = edgeX();
                int px = (int) (mStartX + dx);
                mDrawerLp.x = Math.max(minX, Math.min(maxX, px));
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
                if (!moved) {
                    if (mGuestOpen) {
                        // 全开态点把手：收回应用（一步到位，最顺手）
                        winClose();
                        return true;
                    }
                    // 点按 toggle：贴边 ↔ 展开
                    boolean open = mDrawerLp.x < (edgeX() + openX()) / 2;
                    snapTo(open ? edgeX() : openX());
                } else {
                    // 松手吸附：拉出过半 → 展开；否则贴边
                    int mid = (edgeX() + openX()) / 2;
                    snapTo(mDrawerLp.x < mid ? openX() : edgeX());
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
                // 取消时按当前位姿就近吸附
                int mid = (edgeX() + openX()) / 2;
                snapTo(mDrawerLp.x < mid ? openX() : edgeX());
                mDragging = false;
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 全屏窗户

    /** 窗户拖动开始：面板收起（容器只剩把手）、窗帘上幕、应用瞬时全屏。 */
    private void beginWinDrag() {
        mWinDrag = true;
        // 面板收起：窗户模式容器只剩把手（面板 GONE），不挡底下的真应用
        View panel = mDrawer.findViewById(R.id.drawer_panel);
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        // 窗帘（全屏深色半透明，translationX 跟手）
        showCurtain();
        // 拖出：把真应用瞬时调到前台（窗帘底下），全程 live 内容
        if (mWinPullOut && mPullTaskId >= 0) {
            // 2.1.73：先记住窗户底下的 task（收回时恢复它，绝不拉宿主主界面）
            mBeneathTaskId = captureBeneathTask();
            bringGuestRobust();
        }
    }

    /**
     * 2.1.73：拉出前记一下当前前台 task（= 窗户底下的界面）。
     * getRunningTasks 只能看到自己 uid 的 task（guest 以宿主 stub 名义注册，
     * 同 uid 可见）——拿得到宿主自己的；拿不到（别的 app/桌面）返回 -1，
     * 收回时走 HOME 兜底。
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
                        am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION);
                        TLog.i("Float", "win: moveTaskToFront " + taskId);
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

    /** 窗户全开：应用继续全屏，球吸附左缘，窗帘滑出界（方向=拖动来向）。 */
    private void winOpen() {
        mGuestOpen = true;
        mHandle.setText("›");
        ballAnimTo(0);
        dropCurtain(mWinPullOut ? mScreenW : -mScreenW);
    }

    /** 窗户收回：应用送后台，球回贴边。 */
    private void winClose() {
        mGuestOpen = false;
        mHandle.setText("‹");
        // 2.1.73：恢复「窗户底下的界面」（拉出前记的 task / HOME 兜底），
        // 绝不拉宿主主界面——用户反馈：收回后见到的应是原来的界面。
        restoreBeneath();
        ballAnimTo(edgeX());
        dropCurtain(mWinPullOut ? mScreenW : -mScreenW);
        // 面板恢复（下次拖动/点按可用）
        View panel = mDrawer.findViewById(R.id.drawer_panel);
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
        }
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

    /** 窗户目标预取/刷新（DOWN 时调一次保证数据新鲜，label 用面板缓存）。 */
    private void refreshRunCache() {
        try {
            List<com.lody.virtual.remote.AppTaskInfo> r = VBox.runningTasks();
            mRunCache = r;
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
        mHandle.setText(opening ? "›" : "‹");
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
        List<com.lody.virtual.remote.AppTaskInfo> running = VBox.runningTasks();
        refreshRunCache();
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
                        VBox.launch(FloatingService.this, pkg0, 0);
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
                    VBox.launch(FloatingService.this, e.packageName, e.userId);
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
