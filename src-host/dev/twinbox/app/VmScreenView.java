package dev.twinbox.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TwinBox 2.3.1：窗户画面（VMOS 浮窗 Demo 的"获取画面"实现移植）。
 *
 * 【路线拍板（2026-10-10 真机反馈）】弃用 MediaProjection"获取屏幕内容"链路
 * （授权烦、ColorOS 下投出来的还是主屏内容），改用 Demo 的实现：窗户内容 =
 * 宿主进程内自绘 View——实时渲染、可交互，VMOS 原版语义（libmci 视频流
 * 也是宿主 View，我们用自绘替视频流，TwinBox 的"视频流"=容器应用网格）。
 *
 * 内容：动态背景 + 粒子 + 时钟 + 容器应用网格（真实 installed 数据，
 * 后台线程加载）+ 运行中标记。点应用 = 主屏启动容器应用并收回窗户。
 */
public class VmScreenView extends View {

    /** 点击应用的回调（Service 注入：启动 + 收窗）。 */
    public interface Launcher {
        void onLaunchApp(String pkg, int userId);
    }

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat mClockFmt =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    /** shader 缓存（Demo v1.9 教训：每帧 new shader = 纯垃圾） */
    private RadialGradient mGlow;
    private float mGlowHue = Float.NaN;
    private int mGlowW, mGlowH;

    private final List<float[]> mParticles = new ArrayList<>();
    private final Launcher mLauncher;

    /** 容器应用（后台加载） */
    private List<VBox.VAppEntry> mApps = new ArrayList<>();
    /** 运行中包名 */
    private final Set<String> mRunning = new HashSet<>();
    private volatile boolean mLoaded = false;
    private int mTouchIndex = -1;

    public VmScreenView(Context context, Launcher launcher) {
        super(context);
        mLauncher = launcher;
        setClickable(true);
        for (int i = 0; i < 18; i++) {
            mParticles.add(new float[]{
                    (float) Math.random(), (float) Math.random(),
                    0.15f + (float) Math.random() * 0.35f,
                    6 + (float) Math.random() * 22,
            });
        }
        reload();
    }

    /** 后台线程加载容器应用 + 运行状态（binder 调用不占主线程，2.2.2 教训）。 */
    public void reload() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<VBox.VAppEntry> apps = VBox.listInstalled();
                    Set<String> run = new HashSet<>();
                    try {
                        for (com.lody.virtual.remote.AppTaskInfo t : VBox.runningTasks()) {
                            if (t != null && t.baseIntent != null
                            && t.baseIntent.getComponent() != null) {
                                run.add(t.baseIntent.getComponent().getPackageName());
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                    mRunning.clear();
                    mRunning.addAll(run);
                    if (apps != null) {
                        mApps = apps;
                    }
                    mLoaded = true;
                    postInvalidateOnAnimation();
                } catch (Throwable t) {
                    TLog.w("VmScreen", "reload fail: " + t);
                }
            }
        }, "tb-win-reload").start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 心跳门控（Demo v1.1 教训）：窗户 GONE 时 isShown()=false 停帧，
        // 不对空 surface 逐帧空重绘（闪屏根因）。
        if (isShown()) {
            postInvalidateOnAnimation();
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float t = android.os.SystemClock.elapsedRealtime() / 1000f % 3600f;

        // 动态背景：深色 + 色相缓慢漂移的光晕（缓存重建，色相每 ~1.5° 才换）
        float hue = (t * 8f) % 360f;
        if (mGlow == null || mGlowW != w || mGlowH != h
                || Float.isNaN(mGlowHue) || Math.abs(hue - mGlowHue) >= 1.5f) {
            int glow = Color.HSVToColor(new float[]{hue, 0.55f, 0.30f});
            mGlow = new RadialGradient(
                    w * 0.5f, h * 0.32f, Math.max(w, h) * 0.75f,
                    glow, 0xFF070A12, Shader.TileMode.CLAMP);
            mGlowW = w;
            mGlowH = h;
            mGlowHue = hue;
        }
        mBgPaint.setShader(mGlow);
        canvas.drawRect(0, 0, w, h, mBgPaint);

        // 漂浮粒子
        mTextPaint.setColor(0x66FFFFFF);
        for (float[] p : mParticles) {
            float px = ((p[0] + t * p[2] * 0.06f) % 1f) * w;
            float py = ((p[1] + t * p[2] * 0.11f) % 1f) * h;
            mTextPaint.setAlpha((int) (40 + p[2] * 90));
            canvas.drawCircle(px, py, p[3] * 0.5f, mTextPaint);
        }

        // 时钟 + 标题
        mClockPaint.setColor(0xFFEDF2FF);
        mClockPaint.setTextAlign(Paint.Align.CENTER);
        mClockPaint.setTextSize(Math.min(w, h) * 0.085f);
        canvas.drawText(mClockFmt.format(new Date()), w / 2f, h * 0.16f, mClockPaint);
        mTextPaint.setColor(0xFF8A93B0);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(Math.min(w, h) * 0.026f);
        canvas.drawText("TwinBox 容器 · 拖左缘圆球可收回窗户", w / 2f, h * 0.205f, mTextPaint);

        // 应用网格（真实容器数据；未加载完画提示）
        int n = Math.min(16, mApps.size());
        float gridTop = h * 0.28f;
        float cell = Math.min(w / 5f, (h * 0.62f) / 4f);
        if (!mLoaded) {
            mTextPaint.setTextSize(Math.min(w, h) * 0.03f);
            canvas.drawText("正在读取容器应用…", w / 2f, h * 0.5f, mTextPaint);
            return;
        }
        if (n == 0) {
            mTextPaint.setTextSize(Math.min(w, h) * 0.03f);
            canvas.drawText("容器内暂无应用 · 去主界面安装", w / 2f, h * 0.5f, mTextPaint);
            return;
        }
        for (int i = 0; i < n; i++) {
            VBox.VAppEntry e = mApps.get(i);
            int row = i / 4, col = i % 4;
            float cx = w * (0.16f + col * 0.225f);
            float cy = gridTop + row * cell * 1.5f + cell * 0.5f;
            boolean touch = mTouchIndex == i;

            // 图标底座
            mDotPaint.setColor(touch ? 0xFF2E4B8F : 0xFF141C30);
            canvas.drawRoundRect(cx - cell * 0.36f, cy - cell * 0.36f,
                    cx + cell * 0.36f, cy + cell * 0.36f,
                    cell * 0.16f, cell * 0.16f, mDotPaint);

            // 真实图标
            try {
                Drawable d = e.icon;
                if (d != null) {
                    int isz = (int) (cell * 0.56f);
                    d.setBounds((int) cx - isz / 2, (int) cy - isz / 2,
                            (int) cx + isz / 2, (int) cy + isz / 2);
                    d.draw(canvas);
                }
            } catch (Throwable ignore) {
            }

            // 运行中标记（左上绿点）
            if (mRunning.contains(e.packageName)) {
                mDotPaint.setColor(0xFF35D07F);
                canvas.drawCircle(cx - cell * 0.36f, cy - cell * 0.36f, cell * 0.07f, mDotPaint);
            }

            // 标签（截断）
            String label = e.label == null ? e.packageName : e.label;
            mLabelPaint.setColor(touch ? 0xFFEDF2FF : 0xFFB7C1D8);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTextSize(cell * 0.15f);
            while (mLabelPaint.measureText(label) > cell * 1.1f && label.length() > 1) {
                label = label.substring(0, label.length() - 1);
            }
            canvas.drawText(label, cx, cy + cell * 0.62f, mLabelPaint);
        }

        // 底部提示
        mTextPaint.setTextSize(Math.min(w, h) * 0.022f);
        canvas.drawText("点应用启动 · 绿点=运行中", w / 2f, h * 0.94f, mTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int w = getWidth();
        final int h = getHeight();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchIndex = hitApp(event.getX(), event.getY(), w, h);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (mTouchIndex >= 0 && mTouchIndex < mApps.size()
                        && mTouchIndex == hitApp(event.getX(), event.getY(), w, h)) {
                    VBox.VAppEntry e = mApps.get(mTouchIndex);
                    if (mLauncher != null) {
                        mLauncher.onLaunchApp(e.packageName, e.userId);
                    }
                }
                mTouchIndex = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mTouchIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int hitApp(float x, float y, int w, int h) {
        int n = Math.min(16, mApps.size());
        float gridTop = h * 0.28f;
        float cell = Math.min(w / 5f, (h * 0.62f) / 4f);
        for (int i = 0; i < n; i++) {
            int row = i / 4, col = i % 4;
            float cx = w * (0.16f + col * 0.225f);
            float cy = gridTop + row * cell * 1.5f + cell * 0.5f;
            if (Math.hypot(x - cx, y - cy) < cell * 0.45f) {
                return i;
            }
        }
        return -1;
    }
}
