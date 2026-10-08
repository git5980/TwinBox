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
 * 悬浮抽屉（VMOS 式）：贴右缘把手 + 侧面板一体化。
 *
 * TwinBox 2.1.63 重写（相对 2.0.8 的球+居中面板）：
 *  - 单一 window 容器 [把手竖条][面板]，贴边态只露把手（面板停屏外）；
 *  - 横向拖把手：面板从右缘跟手拉出（实时位移，非弹窗）；
 *  - 反向拖：跟手收回；
 *  - 松手：拉出过半 → 吸附展开；否则吸附回贴边（Decelerate 动画）；
 *  - 点按把手：toggle（动画展开/收回，兼容老习惯）；
 *  - 纵向拖：整抽屉上下挪位（y clamp 屏内）；
 *  - 展开态点外部（FLAG_WATCH_OUTSIDE_TOUCH → ACTION_OUTSIDE）：收回。
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
                .setContentText("拖动把手拉出容器应用；拖回右缘收起")
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
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mStartX = mDrawerLp.x;
                mStartY = mDrawerLp.y;
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - mDownX;
                float dy = event.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                    mDragging = true;
                }
                if (!mDragging) {
                    return true;
                }
                // 横向：贴边位 ↔ 展开位之间跟手（clamp）
                int minX = openX();
                int maxX = edgeX();
                int nx = (int) (mStartX + dx);
                mDrawerLp.x = Math.max(minX, Math.min(maxX, nx));
                // 纵向：整抽屉挪位（clamp 屏内）
                int handleH = Math.max(1, mHandle.getHeight());
                int ny = (int) (mStartY + dy);
                mDrawerLp.y = Math.max(0, Math.min(mScreenH - handleH, ny));
                applyLayout();
                return true;
            case MotionEvent.ACTION_UP:
                boolean moved = Math.abs(event.getRawX() - mDownX) > 12
                        || Math.abs(event.getRawY() - mDownY) > 12;
                if (!moved) {
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
            case MotionEvent.ACTION_CANCEL:
                // 取消时按当前位姿就近吸附
                int mid = (edgeX() + openX()) / 2;
                snapTo(mDrawerLp.x < mid ? openX() : edgeX());
                mDragging = false;
                return true;
            default:
                return false;
        }
    }

    /** 吸附动画（Decelerate，200ms）+ 箭头态切换 */
    private void snapTo(final int targetX) {
        cancelSnap();
        final int from = mDrawerLp.x;
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
        mSnapAnim.start();
        // 箭头：展开态 ›（向右推回）；贴边态 ‹（向左拉出）
        mHandle.setText(targetX <= (edgeX() + openX()) / 2 ? "›" : "‹");
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
        List<VBox.VAppEntry> apps;
        try {
            apps = VBox.listInstalled();
        } catch (Throwable t) {
            apps = null;
            TLog.e("Float", "listInstalled fail", t);
        }
        LinearLayout box = (LinearLayout) mDrawer.findViewById(R.id.panel_items);
        if (apps == null || apps.isEmpty()) {
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
