package dev.twinbox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 悬浮球：点击展开容器应用快捷面板（VMOS 悬浮窗同款体验）。
 *
 * TwinBox 2.0.8 修复清单（相对 2.0.7）：
 *
 *  1) 触摸分发断了半个手势。
 *     createBall() 的 OnTouchListener 里 ACTION_DOWN 分支 return false。
 *     View.dispatchTouchEvent 拿到 false 后转 onTouchEvent()，而 floating_ball.xml 的
 *     根 ImageView 没有 clickable/focusable/tooltip，onTouchEvent 也返回 false ——
 *     整棵视图树不消费 ACTION_DOWN，mFirstTouchTarget 保持 null，
 *     后续 ACTION_MOVE 会被当拦截处理（转 CANCEL），ACTION_UP 根本到不了这个 listener。
 *     结果：悬浮球既拖不动，点了也不展开，屏幕上就是一张点不动的图。
 *     修法：ACTION_DOWN 也 return true。
 *
 *  2) 容器为空时 togglePanel() 直接崩。
 *     mPanel 只做过 inflate、从没 mWm.addView()，空容器分支里就 mWm.removeView(mPanel)，
 *     WindowManagerGlobal.removeView() 抛 IllegalArgumentException:
 *     "View=... not attached to window manager"，从触摸分发一路冒到顶层。
 *     修法：统一走 safeRemove()，attach 状态 + try/catch 双保险。
 *
 *  3) 面板弹出来没有关闭手段。
 *     原来只能再点一次球（而球本来就是废的）。现在补三路：面板内关闭按钮、
 *     点击面板外部区域（FLAG_WATCH_OUTSIDE_TOUCH）、再点球收起。
 *
 *  4) 新增 isRunning()，供 MainActivity 的悬浮球按钮做开关态显示。
 */
public class FloatingService extends Service {

    /** 供 MainActivity 同步按钮选中态；进程内单例语义，不需要同步到引擎 */
    private static volatile boolean sRunning = false;

    public static boolean isRunning() {
        return sRunning;
    }

    private WindowManager mWm;
    private View mBall;
    private View mPanel;
    private final WindowManager.LayoutParams mBallLp =
            new WindowManager.LayoutParams();
    private float mDownX, mDownY;
    private int mLastX, mLastY;
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
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Throwable t) {
            // Android 12+ 后台起 FGS / Android 14+ specialUse 权限缺失都会走到这里。
            // 前台化失败不该让悬浮球整体失效，退化成普通服务继续用。
            TLog.e("Float", "startForeground fail, degrade to background", t);
        }
        createBall();
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
                .setContentTitle("双生盒悬浮球")
                .setContentText("点按悬浮球展开容器应用；通知点击回桌面")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createBall() {
        mBall = LayoutInflater.from(this).inflate(R.layout.floating_ball, null, false);
        final WindowManager.LayoutParams lp = mBallLp;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 20;
        lp.y = 400;
        mBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = event.getRawX();
                        mDownY = event.getRawY();
                        mLastX = lp.x;
                        mLastY = lp.y;
                        mDragging = false;
                        // TwinBox 2.0.8：必须是 true。返回 false 的话后续 MOVE/UP 都收不到
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = mLastX + (int) (event.getRawX() - mDownX);
                        lp.y = mLastY + (int) (event.getRawY() - mDownY);
                        try {
                            mWm.updateViewLayout(mBall, lp);
                        } catch (Throwable t) {
                            TLog.w("Float", "updateViewLayout fail: " + t);
                        }
                        if (Math.abs(event.getRawX() - mDownX) > 12
                                || Math.abs(event.getRawY() - mDownY) > 12) {
                            mDragging = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean moved = Math.abs(event.getRawX() - mDownX) > 12
                                || Math.abs(event.getRawY() - mDownY) > 12;
                        if (!moved) {
                            togglePanel();
                        } else if (mPanel != null) {
                            // 拖动时顺手把面板收掉，避免面板和球脱节
                            closePanel();
                        }
                        mDragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mDragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            mWm.addView(mBall, lp);
        } catch (Throwable t) {
            TLog.e("Float", "addView ball fail", t);
        }
    }

    private void togglePanel() {
        if (mPanel != null) {
            closePanel();
            return;
        }
        mPanel = LayoutInflater.from(this).inflate(R.layout.floating_panel, null, false);
        List<VBox.VAppEntry> apps;
        try {
            apps = VBox.listInstalled();
        } catch (Throwable t) {
            apps = null;
            TLog.e("Float", "listInstalled fail", t);
        }
        if (apps == null || apps.isEmpty()) {
            // TwinBox 2.0.8：原来这里直接 mWm.removeView(mPanel)，
            // 而这个 view 从没 addView 过 → IllegalArgumentException 崩溃。
            // 现在顺手给个提示，并且绝不 remove 未 attach 的 view。
            Toast.makeText(this, "容器内暂无应用", Toast.LENGTH_SHORT).show();
            mPanel = null;
            return;
        }
        LinearLayout box = (LinearLayout) mPanel.findViewById(R.id.panel_items);
        ImageButton close = (ImageButton) mPanel.findViewById(R.id.panel_close);
        if (close != null) {
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closePanel();
                }
            });
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
                    closePanel();
                }
            });
            box.addView(item);
            shown++;
        }
        // 点击面板外区域收起（原来是纯 NOT_FOCUSABLE，点外面没有任何反应）
        mPanel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    closePanel();
                    return true;
                }
                return false;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.width = getResources().getDimensionPixelSize(R.dimen.panel_width);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.format = PixelFormat.RGBA_8888;
        try {
            mWm.addView(mPanel, lp);
        } catch (Throwable t) {
            TLog.e("Float", "addView panel fail", t);
            mPanel = null;
        }
    }

    /** 安全移除面板：先判 attach，再兜异常，绝不因为 removeView 把服务带崩 */
    private void closePanel() {
        if (mPanel == null) {
            return;
        }
        View p = mPanel;
        mPanel = null;
        try {
            if (isAttached(p)) {
                mWm.removeView(p);
            } else {
                TLog.w("Float", "panel not attached, skip removeView");
            }
        } catch (Throwable t) {
            TLog.w("Float", "removeView panel fail: " + t);
        }
    }

    private static boolean isAttached(View v) {
        if (v == null) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            return v.isAttachedToWindow();
        }
        return v.getWindowToken() != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closePanel();
        if (mBall != null) {
            try {
                if (isAttached(mBall)) {
                    mWm.removeView(mBall);
                }
            } catch (Throwable t) {
                TLog.w("Float", "removeView ball fail: " + t);
            }
            mBall = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        sRunning = false;
    }
}
