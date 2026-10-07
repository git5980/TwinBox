package dev.twinbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;

import java.util.List;

/**
 * 容器桌面：VM 内应用九宫格（VMOS 主界面同构）。
 *
 * TwinBox 2.0.8 修复清单（相对 2.0.7）：
 *  - 底部「装 APK」「克隆应用」「悬浮球」三个按钮此前从未 findViewById，点击无任何响应；
 *  - 「安装应用 / 悬浮球 / 结束全部」原先就写在 onCreateOptionsMenu 里，代码本身没错，
 *    但主题 Theme.Material.NoActionBar 下没有 ActionBar、targetSdk 30 又关掉 legacy 浮动菜单，
 *    整份菜单永不可达。2.0.8 把主题换成 Theme.Material，让原实现的菜单真正显示出来，
 *    菜单 XML 独立到 res/menu/menu_main.xml；
 *  - 多开出的第 2、3 个分身原先只能看角标 ×N，点图标永远只启动 users[0]；现在多分身态
 *    点击会先弹分身选择器；
 *  - 「应用信息」原先跳宿主 ACTION_APPLICATION_DETAILS_SETTINGS，容器专属包在宿主没安装，
 *    只会打开空白页；改为自建详情弹窗，展示引擎侧真实信息。
 */
public class MainActivity extends Activity {

    /** InstallActivity 的启动模式：收到后直接拉起 SAF 选文件 */
    public static final String EXTRA_AUTO_PICK = "dev.twinbox.app.extra.AUTO_PICK";
    /** TwinBox 2.1.49：主页选完 APK 后带 Uri 跳安装中心执行安装 */
    public static final String EXTRA_INSTALL_URI = "dev.twinbox.app.extra.INSTALL_URI";
    /** TwinBox 2.1.49：主页自己的选包请求码（InstallActivity 用 41） */
    private static final int REQ_PICK_APK = 42;

    private GridView mGrid;
    private AppAdapter mAdapter;
    private TextView mStatus;
    private LinearLayout mEmpty;
    // TwinBox 2.1.49：底部操作栏（空容器时整条隐藏，见 reload()）
    private LinearLayout mBottomBar;
    private ImageButton mFloatBtn;

    @Override
    protected void onStart() {
        super.onStart();
        // TwinBox 2.1.38：宿主界面回到前台时补挂保活。
        // 场景：guest 在后台活着（:x/:pN 不随本 Activity 生死），但 KeepAliveService
        // 已被低内存回收或 o-stop 打断——此刻用户划掉任务，OPlus 看到本 uid 无 FGS
        // 就会团灭。宿主 UI 在前台时起 FGS 100% 合法（uid 级豁免），这是最可靠的
        // 重挂时机。guest 全退时 VAMS.onProcessDied 会自动撤掉，不会常驻通知。
        if (VBox.isCoreReady() && VBox.isAnyGuestRunning()) {
            KeepAliveService.start(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mGrid = (GridView) findViewById(R.id.grid);
        mStatus = (TextView) findViewById(R.id.status);
        mEmpty = (LinearLayout) findViewById(R.id.empty);
        mBottomBar = (LinearLayout) findViewById(R.id.bottom_bar);
        mFloatBtn = (ImageButton) findViewById(R.id.btn_float);

        mAdapter = new AppAdapter();
        mGrid.setAdapter(mAdapter);
        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onAppClick(position);
            }
        });
        mGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showOps(mAdapter.getItem(position));
                return true;
            }
        });
        findViewById(R.id.btn_install_empty).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TwinBox 2.1.49：空态按钮直接拉起 SAF 文件选择器，
                // 不再先跳「克隆应用」界面（InstallActivity）再二次跳选包。
                pickApk();
            }
        });
        // TwinBox 2.0.8：这三个按钮此前没有任何监听，纯摆设
        findViewById(R.id.btn_add_apk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TwinBox 2.1.49：同空态按钮，直接选包（原来是带 EXTRA_AUTO_PICK
                // 跳 InstallActivity 再自动弹选择器——白闪一次克隆界面）。
                pickApk();
            }
        });
        findViewById(R.id.btn_clone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InstallActivity.class));
            }
        });
        mFloatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFloatingBall();
            }
        });
        ensurePermissions();
    }

    /**
     * 点九宫格图标：多分身态先让用户选分身，单分身直接启动。
     * 2.0.7 及以前永远只启动 users[0]，多开出来的分身没有入口。
     */
    private void onAppClick(int position) {
        final VBox.VAppEntry e = mAdapter.getItem(position);
        if (e == null) {
            return;
        }
        if (e.users != null && e.users.length > 1) {
            CharSequence[] items = new CharSequence[e.users.length];
            for (int i = 0; i < e.users.length; i++) {
                items[i] = getString(R.string.dialog_clone_item) + e.users[i];
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_clone_title)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which >= 0 && which < e.users.length) {
                                VBox.launch(MainActivity.this, e.packageName, e.users[which]);
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            VBox.launch(this, e.packageName, e.userId);
        }
    }

    /** 悬浮球开关：起 / 停 FloatingService 并同步按钮选中态 */
    private void toggleFloatingBall() {
        if (!Settings.canDrawOverlays(this)) {
            askOverlayPermission();
            return;
        }
        boolean running = FloatingService.isRunning();
        Intent i = new Intent(this, FloatingService.class);
        if (running) {
            stopService(i);
            Toast.makeText(this, R.string.toast_float_off, Toast.LENGTH_SHORT).show();
        } else {
            try {
                startService(i);
                Toast.makeText(this, R.string.toast_float_on, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                TLog.e("Main", "start FloatingService fail", t);
                Toast.makeText(this, "悬浮球启动失败：" + t, Toast.LENGTH_LONG).show();
            }
        }
        syncFloatBtn();
    }

    private void syncFloatBtn() {
        boolean running = FloatingService.isRunning();
        mFloatBtn.setSelected(running);
        mFloatBtn.setColorFilter(running ? 0xFF0B1020 : 0xFFFFFFFF);
    }

    /** 权限申请中心：主动申请全部所需权限，不让用户猜 */
    private void ensurePermissions() {
        android.content.SharedPreferences sp = getSharedPreferences("perm", MODE_PRIVATE);
        boolean guided = sp.getBoolean("guided_1", false);
        // 1) 所有文件访问（日志落盘 Download/）
        if (!android.os.Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("建议开启「所有文件访问」权限")
                    .setMessage("运行日志默认已写到 Download/TwinBox/（免授权通道）；授权后改为直接写公共目录，稳定性更好，也方便排查克隆与安装失败。")
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Throwable t) {
                                try {
                                    startActivity(new Intent(
                                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                } catch (Throwable t2) {
                                    TLog.e("Main", "open all-files settings fail", t2);
                                }
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
            return;
        }
        // 2) 通知权限（Android 13+，悬浮球前台服务需要）
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            return;
        }
        // 3) 悬浮窗（悬浮球）
        if (!android.provider.Settings.canDrawOverlays(this)) {
            askOverlayPermission();
            return;
        }
        if (!guided) {
            sp.edit().putBoolean("guided_1", true).apply();
            TLog.i("Main", "permissions all granted");
        }
    }

    private void askOverlayPermission() {
        new AlertDialog.Builder(this)
                .setTitle("需要「悬浮窗」权限")
                .setMessage("用于显示双生盒悬浮球，可从任意界面快捷回到容器应用。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Throwable t) {
                            TLog.e("Main", "open overlay settings fail", t);
                        }
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncFloatBtn();
        reload();
        // 状态栏提示日志落盘位置（TwinBox 2.1.25：公共 Download 免授权也能落）
        if (mStatus != null) {
            mStatus.setText("日志: " + TLog.dirDesc()
                    + (TLog.isPublic() ? "" : "  (私有目录·未落公共)"));
        }
    }

    /**
     * TwinBox 2.1.49：直接拉起系统文件选择器选 APK（与 InstallActivity.pickApk 同款 intent）。
     * 选完在 onActivityResult 里带 Uri 跳 InstallActivity 执行安装——
     * 安装进度/结果 Toast/列表刷新全在 InstallActivity，不在主页重复实现。
     */
    private void pickApk() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 与 InstallActivity 一致：部分 OEM 选择器对 APK MIME 返回空，放宽到 */*
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // 带 Uri 跳安装中心执行安装（它有完整的复制/安装/进度/Toast 流程）
            Intent i = new Intent(this, InstallActivity.class);
            i.putExtra(EXTRA_INSTALL_URI, data.getData().toString());
            startActivity(i);
        }
    }

    private void reload() {
        TLog.i("Main", "reload: listing installed apps...");
        // 2.0.8：VirtualCore.get() 是非空单例，本地判断它非 null 永远成立。
        // 改用引擎真实启动标志 isStartup()，启动失败时能如实反馈而不是显示空列表。
        if (!VirtualCore.get().isStartup()) {
            mStatus.setText("引擎初始化异常");
            mAdapter.swap(null);
            mEmpty.setVisibility(View.VISIBLE);
            mGrid.setVisibility(View.GONE);
            return;
        }
        List<VBox.VAppEntry> apps = VBox.listInstalled();
        mAdapter.swap(apps);
        int multi = 0;
        for (VBox.VAppEntry e : apps) {
            if (e.users != null && e.users.length > 1) {
                multi++;
            }
        }
        mStatus.setText("双生盒 · 容器内 " + apps.size() + " 个应用"
                + (multi > 0 ? " · " + multi + " 个已多开" : ""));
        mEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        mGrid.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);
        // TwinBox 2.1.49：底部操作栏与空态互斥——空容器时中间的
        // 「安装 APK」空态按钮就是唯一入口，底部整条（装 APK / 克隆 / 悬浮球）
        // 隐藏避免重复；有应用后再回来（克隆/悬浮球此时才有意义）。
        mBottomBar.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showOps(final VBox.VAppEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(new CharSequence[]{
                        getString(R.string.dialog_ops_clone),
                        getString(R.string.dialog_ops_kill),
                        getString(R.string.dialog_ops_uninstall),
                        getString(R.string.dialog_ops_info),
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            int uid = VBox.cloneToNewUser(e.packageName);
                            Toast.makeText(MainActivity.this,
                                    uid >= 0 ? "分身已创建（#" + uid + "）" : "多开失败",
                                    Toast.LENGTH_SHORT).show();
                            reload();
                        } else if (which == 1) {
                            VirtualCore.get().killApp(e.packageName, e.userId);
                            Toast.makeText(MainActivity.this, "已结束", Toast.LENGTH_SHORT).show();
                        } else if (which == 2) {
                            if (VBox.uninstall(e.packageName)) {
                                Toast.makeText(MainActivity.this, "已卸载", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "卸载失败", Toast.LENGTH_SHORT).show();
                            }
                            reload();
                        } else {
                            showInfo(e);
                        }
                    }
                })
                .show();
    }

    /**
     * 2.0.8：容器内应用详情。
     * 2.0.7 跳的是宿主 ACTION_APPLICATION_DETAILS_SETTINGS，容器专属包在宿主没安装，
     * 打开的是空白页且不抛异常，兜底 Toast 也不会出现。现在直接展示引擎侧信息。
     */
    private void showInfo(VBox.VAppEntry e) {
        String hostInstalled = getString(R.string.info_no);
        String version = getString(R.string.info_unknown);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(e.packageName, 0);
            hostInstalled = getString(R.string.info_yes);
            if (pi.versionName != null) {
                version = pi.versionName;
            }
        } catch (Throwable ignore) {
            // 宿主没装这个包，正常
        }
        String apkPath = getString(R.string.info_unknown);
        try {
            com.lody.virtual.remote.InstalledAppInfo info =
                    VirtualCore.get().getInstalledAppInfo(e.packageName, 0);
            if (info != null) {
                apkPath = info.getApkPath();
            }
        } catch (Throwable t) {
            TLog.w("Main", "getApkPath fail: " + t);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.info_pkg)).append("：").append(e.packageName).append('\n');
        sb.append(getString(R.string.info_version)).append("：").append(version).append('\n');
        sb.append(getString(R.string.info_copies)).append("：").append(e.copyCount()).append('\n');
        sb.append(getString(R.string.info_users)).append("：");
        if (e.users != null) {
            for (int u : e.users) {
                sb.append(u).append(' ');
            }
        }
        sb.append('\n');
        sb.append(getString(R.string.info_apk)).append("：").append(apkPath).append('\n');
        sb.append(getString(R.string.info_host)).append("：").append(hostInstalled);
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * TwinBox 2.0.8：菜单实现恢复工作。
     *
     * 2.0.7 的这两个方法明明写好了，却因为主题 Theme.Material.NoActionBar 而没有
     * ActionBar 承载 —— 没有溢出按钮，targetSdk 30 又关掉 legacy 浮动菜单，
     * onCreateOptionsMenu 压根不会被调用，三项菜单全是死代码。
     * 2.0.8 把主题换成 Theme.Material（框架提供 ActionBar），
     * 菜单项从 res/menu/menu_main.xml inflate，即可正常显示并响应。
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        // TwinBox 2.1.49：menu_install / menu_float 两项已删（与底部操作栏
        // 重复），这里只留「结束全部」。
        if (id == R.id.menu_kill_all) {
            VBox.killAll();
            Toast.makeText(this, R.string.toast_kill_all, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class AppAdapter extends BaseAdapter {
        private List<VBox.VAppEntry> data = new java.util.ArrayList<VBox.VAppEntry>();

        void swap(List<VBox.VAppEntry> d) {
            data = (d == null ? new java.util.ArrayList<VBox.VAppEntry>() : d);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public VBox.VAppEntry getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_va_app, parent, false);
            }
            VBox.VAppEntry e = getItem(position);
            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView label = (TextView) v.findViewById(R.id.label);
            TextView badge = (TextView) v.findViewById(R.id.badge);
            Drawable d = e.icon == null ? getResources().getDrawable(android.R.drawable.sym_def_app_icon) : e.icon;
            icon.setImageDrawable(d);
            label.setText(e.label == null ? e.packageName : e.label);
            int copies = e.copyCount();
            badge.setVisibility(copies > 1 ? View.VISIBLE : View.GONE);
            badge.setText("×" + copies);
            return v;
        }
    }
}
