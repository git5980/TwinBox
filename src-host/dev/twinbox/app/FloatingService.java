package dev.twinbox.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * TwinBox 2.3.1 悬浮球（VMOS 式）。
 *
 * 【架构（三层拍板史）】
 *  1. 手势层：浮窗 Demo v2.0（十轮真机迭代）——球窗口=球本身（固定小窗，
 *     拖动逐帧 updateViewLayout），窗户露出量=屏宽−球x 的绑定语义；
 *  2. 画面层：浮窗 Demo 的"获取画面"实现（2026-10-10 真机反馈拍板）——
 *     窗户内容=宿主自绘 VmScreenView（容器应用网格），**不再用 MediaProjection
 *     获取屏幕内容**（授权链路烦、ColorOS 下投出来的是主屏内容不是容器应用）；
 *     VMOS 原版语义：浮窗里的画面是宿主 View 层的东西（libmci 视频流），
 *     我们用自绘替视频流——"TwinBox 的视频流=容器应用网格"；
 *  3. 数据层：VBox（VirtualApp 引擎）真实 installed/running 数据，后台线程加载。
 *
 * 交互：球自由拖动（松手停原位）；右半屏向左拖=拉出窗户（绑定跟手），
 * 过半松手全开、不足收回；窗户开着向右拖=收回；全开态点球=一键收回；
 * 平时点球=菜单（运行中+启动区）。窗户里点应用=主屏启动容器应用+收窗。
 */
public class FloatingService extends Service implements VmScreenView.Launcher {

    private static final String TAG = "Float";
    private static final String CH = "twinbox_float";

    static volatile boolean sRunning = false;

    private WindowManager mWm;
    private int mScreenW, mScreenH, mSlop, mBallSize;

    // 球（窗口=球本身）
    private View mBall;
    private WindowManager.LayoutParams mBallLp;

    // 窗户（内容 = VmScreenView 自绘）
    private FrameLayout mWin;
    private VmScreenView mVmView;
    private WindowManager.LayoutParams mWinLp;

    // 菜单
    private LinearLayout mMenu;
    private WindowManager.LayoutParams mMenuLp;

    // 手势状态（Demo v2.0 全套）
    private float mDownX, mDownY;
    private int mStartX, mStartY;
    private boolean mDragging, mWinDrag, mWinOpen, mMenuEatenOnDown, mDeadGesture;

    // 动画（窗户绑定动画：窗带球走）
    private ValueAnimator mWinAnim;
    private ValueAnimator mBallAnim;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        mScreenW = dm.widthPixels;
        mScreenH = dm.heightPixels;
        mSlop = Math.max(12, android.view.ViewConfiguration.get(this).getScaledTouchSlop());
        mBallSize = (int) (60 * dm.density);
        startForeground(9, buildNotification());
        buildWindow();
        buildBall();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "悬浮球",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("TwinBox 悬浮球已开启")
                .setContentText("拖动圆球管理容器应用")
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    // ---------------------------------------------------------------- 窗户

    private void buildWindow() {
        mWin = new FrameLayout(this);
        mWin.setBackgroundColor(0xFF06080D);
        mVmView = new VmScreenView(this, this);
        mWin.addView(mVmView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // 关窗态"真正不存在"：0×0 + GONE + NOT_TOUCHABLE（Demo v1.1 结论）。
        // 熄灭=零像素零触摸零合成；TRANSLUCENT 保证部分拉出时透出桌面。
        mWinLp = new WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        mWinLp.gravity = Gravity.TOP | Gravity.START;
        mWinLp.x = 0;
        mWinLp.y = 0;
        mWin.setVisibility(View.GONE);
        mWin.setTranslationX(mScreenW);
        try {
            mWm.addView(mWin, mWinLp);
        } catch (Throwable t) {
            TLog.e(TAG, "win addView fail", t);
        }
    }

    /** 窗户点亮/熄灭（GONE 根视图 → ViewRootImpl 不建 surface）。 */
    private void setWinActive(boolean on) {
        try {
            if (on) {
                mWinLp.width = mScreenW;
                mWinLp.height = mScreenH;
                mWinLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mWin.setVisibility(View.VISIBLE);
            } else {
                mWin.setVisibility(View.GONE);
                mWinLp.width = 0;
                mWinLp.height = 0;
                mWinLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            mWm.updateViewLayout(mWin, mWinLp);
        } catch (Throwable ignore) {
        }
    }

    /** 窗户露出量（球 x 的绝对函数：露出 = 屏宽 − 球左缘 x）。 */
    private void setWinProgress(int amount) {
        mWin.setTranslationX(Math.max(0, Math.min(mScreenW, mScreenW - amount)));
    }

    // ---------------------------------------------------------------- 球

    private void buildBall() {
        FrameLayout ballRoot = new FrameLayout(this);
        TextView face = new TextView(this);
        face.setBackgroundResource(R.drawable.bg_ball);
        face.setText("◫");
        face.setTextColor(0xFF8FA8E8);
        face.setTextSize(20);
        face.setGravity(Gravity.CENTER);
        // gravity=TOP|START：球窗口=球本身恒定，face 钉在原点（Demo v1.6 教训）
        ballRoot.addView(face, new FrameLayout.LayoutParams(
                mBallSize, mBallSize, Gravity.TOP | Gravity.START));
        mBall = ballRoot;
        mBallLp = new WindowManager.LayoutParams(
                mBallSize, mBallSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mBallLp.gravity = Gravity.TOP | Gravity.START;
        mBallLp.x = mScreenW - mBallSize;
        mBallLp.y = mScreenH / 3;
        mBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });
        try {
            mWm.addView(mBall, mBallLp);
        } catch (Throwable t) {
            TLog.e(TAG, "ball addView fail", t);
            Toast.makeText(this, "悬浮球创建失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 球窗口=球本身：逐帧 updateViewLayout（可见位置永远=逻辑位置）。 */
    private void moveBallTo(int x, int y) {
        mBallLp.x = Math.max(0, Math.min(mScreenW - mBallSize, x));
        mBallLp.y = Math.max(0, Math.min(mScreenH - mBallSize, y));
        try {
            mWm.updateViewLayout(mBall, mBallLp);
        } catch (Throwable t) {
            TLog.w(TAG, "moveBallTo fail: " + t);
        }
    }

    // ---------------------------------------------------------------- 手势（Demo v2.0）

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mBallAnim != null) {
                    mBallAnim.cancel();
                    mBallAnim = null;
                }
                if (mWinAnim != null) {
                    mWinAnim.cancel();
                    mWinAnim = null;
                    // 动画被打断不能停在半空：按目标态重启完成动画（Demo v1.7 P1）
                    if (mWinOpen) {
                        winAnimTo(mScreenW);
                    } else {
                        winAnimTo(0);
                    }
                }
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mStartX = mBallLp.x;
                mStartY = mBallLp.y;
                mDragging = false;
                mWinDrag = false;
                mDeadGesture = false;
                // 菜单盖球防御（Demo v1.2）：按下即吸收关闭
                mMenuEatenOnDown = mMenu != null && mMenu.isAttachedToWindow();
                if (mMenuEatenOnDown) {
                    hideMenu();
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 多指=死亡手势（Demo v1.7 P3）：后续 MOVE 忽略，UP 只复位
                mDeadGesture = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mDeadGesture) {
                    return true;
                }
                float dx = event.getRawX() - mDownX;
                float dy = event.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > mSlop || Math.abs(dy) > mSlop)) {
                    mDragging = true;
                    hideMenu();
                }
                if (!mDragging) {
                    return true;
                }
                // 绑定模式判定（Demo v1.18）：球在右半屏（窗关）/任意（窗开）
                // 横向位移 ≥ touchSlop 即进窗户模式——露出量是球 x 的绝对函数。
                if (!mWinDrag) {
                    boolean canOpen = !mWinOpen && mStartX >= mScreenW / 2;
                    boolean canClose = mWinOpen;
                    if ((canOpen && dx < -mSlop) || (canClose && dx > mSlop)) {
                        mWinDrag = true;
                    }
                }
                if (mWinDrag) {
                    if (mWinAnim != null) {
                        mWinAnim.cancel();
                        mWinAnim = null;
                    }
                    int bx = (int) Math.max(0, Math.min(mScreenW - 8, mStartX + dx));
                    if (mWinLp.width == 0) {
                        // 拉出首帧：先摆露出量再点亮，防全屏内容闪现一帧
                        setWinProgress(mScreenW - bx);
                        setWinActive(true);
                    }
                    setWinProgress(mScreenW - bx);
                    moveBallTo(bx, mStartY + (int) dy);
                } else {
                    // 自由移动（含窗关时纯竖直拖动；clamp 完全可见范围）
                    moveBallTo(mStartX + (int) dx, mStartY + (int) dy);
                }
                mBall.setAlpha(0.55f);
                return true;
            }
            case MotionEvent.ACTION_UP:
                return endGesture(event);
            case MotionEvent.ACTION_CANCEL:
                return endGesture(event);
            default:
                return false;
        }
    }

    private boolean endGesture(MotionEvent event) {
        if (mDeadGesture) {
            mDeadGesture = false;
            mDragging = false;
            mWinDrag = false;
            mMenuEatenOnDown = false;
            mBall.setAlpha(1f);
            return true;
        }
        boolean moved = Math.abs(event.getRawX() - mDownX) > mSlop
                || Math.abs(event.getRawY() - mDownY) > mSlop;
        mBall.setAlpha(1f);
        if (mWinDrag) {
            mWinDrag = false;
            if (mBallLp.x < mScreenW / 2) {
                winOpen();
            } else {
                winClose();
            }
            return true;
        }
        if (!moved) {
            if (mWinOpen) {
                winClose();      // 全开态点球：一键收回
            } else if (!mMenuEatenOnDown) {
                toggleMenu();
            }
        }
        // 松手停原位（Demo v1.8 拍板：不贴边）
        mDragging = false;
        mMenuEatenOnDown = false;
        return true;
    }

    // ---------------------------------------------------------------- 窗户开合（绑定动画：窗带球走）

    private void winOpen() {
        mWinOpen = true;
        if (mWinLp.width == 0) {
            setWinProgress(0);
            setWinActive(true);
        }
        mVmView.reload();
        winAnimTo(mScreenW);
    }

    private void winClose() {
        mWinOpen = false;
        winAnimTo(0);
    }

    /** 窗户露出量动画；动画结束窗口熄灭（被手势打断则不熄，Demo v1.2）。 */
    private void winAnimTo(final int targetAmount) {
        if (mWinAnim != null) {
            mWinAnim.cancel();
        }
        final int from = (int) (mScreenW - mWin.getTranslationX());
        ValueAnimator a = ValueAnimator.ofInt(from, targetAmount);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int amount = (Integer) animation.getAnimatedValue();
                setWinProgress(amount);
                // 绑定语义：球左缘 = 屏宽 − 露出量（窗带球走，Demo v1.14）
                moveBallTo(mScreenW - amount, mBallLp.y);
            }
        });
        final boolean[] cancelled = {false};
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled[0] && !mWinOpen) {
                    setWinActive(false);
                }
            }
        });
        mWinAnim = a;
        a.start();
    }

    // ---------------------------------------------------------------- VmScreenView 回调

    @Override
    public void onLaunchApp(String pkg, int userId) {
        // 窗户里点应用：主屏启动容器应用 + 收窗（画面层是自绘网格，
        // 应用本体跑在主屏——不需要投影/虚拟屏）。
        try {
            VBox.launch(this, pkg, userId);
        } catch (Throwable t) {
            TLog.e(TAG, "launch fail: " + pkg, t);
            Toast.makeText(this, "启动失败：" + t, Toast.LENGTH_SHORT).show();
            return;
        }
        winClose();
    }

    // ---------------------------------------------------------------- 菜单

    private void toggleMenu() {
        if (mMenu != null && mMenu.isAttachedToWindow()) {
            hideMenu();
            return;
        }
        mMenu = (LinearLayout) LayoutInflater.from(this)
                .inflate(R.layout.floating_menu, null, false);
        LinearLayout box = (LinearLayout) mMenu.findViewById(R.id.panel_items);
        fillMenuItems(box);
        mMenuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mMenuLp.gravity = Gravity.TOP | Gravity.START;
        // 垂直避让（Demo v1.2）：球上半屏→菜单下方，反之出上方，永不盖球
        int mw = (int) (240 * getResources().getDisplayMetrics().density);
        int mh = (int) (430 * getResources().getDisplayMetrics().density);
        int mx = Math.max(24, Math.min(mScreenW - mw - 24,
                mBallLp.x + mBallSize / 2 - mw / 2));
        int my = mBallLp.y > mScreenH / 2 ? mBallLp.y - mh - 40 : mBallLp.y + mBallSize + 40;
        my = Math.max(24, Math.min(mScreenH - mh - 24, my));
        mMenuLp.x = mx;
        mMenuLp.y = my;
        mMenu.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        try {
            mWm.addView(mMenu, mMenuLp);
        } catch (Throwable t) {
            TLog.e(TAG, "menu addView fail", t);
        }
    }

    /** 菜单项：运行中（点击=切到前台）+ 启动区（点击=启动）。（后台取数，2.2.2 教训） */
    private void fillMenuItems(final LinearLayout box) {
        box.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<VBox.VAppEntry> apps;
                final List<String> running = new ArrayList<>();
                try {
                    apps = VBox.listInstalled();
                    for (com.lody.virtual.remote.AppTaskInfo t : VBox.runningTasks()) {
                        if (t != null && t.baseIntent != null
                                && t.baseIntent.getComponent() != null) {
                            String p = t.baseIntent.getComponent().getPackageName();
                            if (!running.contains(p)) {
                                running.add(p);
                            }
                        }
                    }
                } catch (Throwable t) {
                    TLog.w(TAG, "menu data fail: " + t);
                    return;
                }
                final List<VBox.VAppEntry> appList =
                        apps == null ? new ArrayList<VBox.VAppEntry>() : apps;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        buildMenuRows(box, appList, running);
                    }
                });
            }
        }, "tb-menu-fill").start();
    }

    private void buildMenuRows(LinearLayout box, List<VBox.VAppEntry> apps, List<String> running) {
        if (mMenu == null) {
            return; // 数据回来前菜单已被关
        }
        if (!running.isEmpty()) {
            TextView head = new TextView(this);
            head.setText("运行中");
            head.setTextColor(0xFF8A93A8);
            head.setTextSize(12);
            head.setPadding(28, 16, 28, 6);
            box.addView(head);
            for (final String pkg : running) {
                VBox.VAppEntry hit = null;
                for (VBox.VAppEntry e : apps) {
                    if (pkg.equals(e.packageName)) {
                        hit = e;
                        break;
                    }
                }
                final String label = hit != null && hit.label != null ? hit.label : pkg;
                TextView item = new TextView(this);
                item.setText("● " + label);
                item.setTextColor(0xFF35D07F);
                item.setTextSize(14);
                item.setPadding(28, 20, 28, 20);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideMenu();
                        try {
                            VBox.launch(FloatingService.this, pkg, 0);
                        } catch (Throwable t) {
                            TLog.e(TAG, "resume launch fail", t);
                        }
                    }
                });
                box.addView(item);
            }
        }
        TextView launchHead = new TextView(this);
        launchHead.setText("启动应用");
        launchHead.setTextColor(0xFF8A93A8);
        launchHead.setTextSize(12);
        launchHead.setPadding(28, 16, 28, 6);
        box.addView(launchHead);
        if (apps.isEmpty() && running.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("容器内暂无应用");
            empty.setTextColor(0xFF8A93A8);
            empty.setTextSize(13);
            empty.setPadding(28, 26, 28, 26);
            box.addView(empty);
            return;
        }
        int shown = 0;
        for (final VBox.VAppEntry e : apps) {
            if (shown >= 8) {
                break; // 菜单最多 8 个，全量去窗户里看
            }
            TextView item = new TextView(this);
            item.setText(e.label == null ? e.packageName : e.label);
            item.setTextColor(0xFFEDF2FF);
            item.setTextSize(14);
            item.setPadding(28, 20, 28, 20);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideMenu();
                    onLaunchApp(e.packageName, e.userId);
                }
            });
            box.addView(item);
            shown++;
        }
    }

    private void hideMenu() {
        if (mMenu != null && mMenu.isAttachedToWindow()) {
            try {
                mWm.removeView(mMenu);
            } catch (Throwable ignore) {
            }
        }
        mMenu = null;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onDestroy() {
        sRunning = false;
        super.onDestroy();
        if (mWinAnim != null) {
            mWinAnim.cancel();
        }
        if (mBallAnim != null) {
            mBallAnim.cancel();
        }
        hideMenu();
        View[] views = {mBall, mWin};
        for (View v : views) {
            if (v != null) {
                try {
                    if (v.isAttachedToWindow()) {
                        mWm.removeView(v);
                    }
                } catch (Throwable t) {
                    TLog.w(TAG, "removeView fail: " + t);
                }
            }
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
    }
}
