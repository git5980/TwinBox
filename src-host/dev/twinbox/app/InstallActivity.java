package dev.twinbox.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.remote.InstallResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用安装中心：本地 APK（SAF）+ 主空间应用一键克隆。
 *
 * TwinBox 2.0.8 修复清单（相对 2.0.7）：
 *
 *  1) 「APK」按钮几乎选不到文件。
 *     setType("application/vnd.android.package-archive") 依赖各家文件选择器都认这个
 *     老旧 MIME；ColorOS/MIUI 等自研 DocumentsUI 上实测拿不到内容。
 *     改为通配任意类型 + CATEGORY_OPENABLE，由宽松 setType 先把文件选出来，
 *     APK 与否在拿到 Uri 之后自己判。
 *
 *  2) 主空间列表在主线程里做重活。
 *     onCreate → reloadHostApps() 直接跑 pm.getInstalledPackages(0)，
 *     每行 getView 又各打一次 VBox.isInstalledInBox() 跨进程 IPC 到 :x 引擎进程。
 *     本机 150+ 非系统应用，首屏几十次串行 binder，滚动还会再打一遍。
 *     改为：后台线程一次性拉列表 + 一次性把「容器内已装包名集合」取回来，
 *     列表和角标都从内存集合读。
 *
 *  3) reloadHostApps() 每次都 new HostAppAdapter()。
 *     克隆完一次重建 adapter，滚动位置全丢。改为复用，只 swap 数据。
 *
 *  4) 新增 MainActivity.EXTRA_AUTO_PICK：从主页「装 APK」进来直接弹文件选择器。
 */
public class InstallActivity extends Activity {

    /**
     * TwinBox 2.1.59：任务分池——克隆阻塞列表的根治。
     * 病根：CloneTask / ListHostTask 全走 AsyncTask 默认串行执行器
     * （单线程排队）。克隆一个大 App（几十秒）期间用户退出再进来，
     * 新页面的 ListHostTask 在队列里永远排不上——「正在读取主空间应用...」
     * 卡到克隆结束（截图实测）。
     * 修法：长任务（克隆/多开/装 APK）走独立单线程池（彼此仍串行，避免
     * 两个安装竞态）；短任务（列表读取）走 AsyncTask 并行池——克隆期间
     * 列表照常秒开。
     */
    private static final java.util.concurrent.Executor INSTALL_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            return new Thread(r, "TB-install");
                        }
                    });


    private static final int REQ_PICK_APK = 41;

    private ListView mList;
    private View mProgressWrap;
    private ProgressBar mProgress;
    private TextView mProgressText;
    private TextView mBtnTasks;
    private InstallCenter.Listener mCenterListener;
    private HostAppAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        mList = (ListView) findViewById(R.id.list);
        mProgressWrap = findViewById(R.id.progress_wrap);
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mProgressText = (TextView) findViewById(R.id.progress_text);
        // TwinBox 2.1.60：原「装 APK」按钮换任务按钮（进度显示）。
        // 选择 APK 的入口收进任务面板顶部；面板实时显示克隆/多开/安装进度
        // ——退出页面再回来进度不再丢（状态在应用级 InstallCenter）。
        mBtnTasks = (TextView) findViewById(R.id.btn_pick_apk);
        mBtnTasks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTasksDialog();
            }
        });
        updateTaskButton();
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        // TwinBox 2.1.49：主页直接选完 APK 带 Uri 进来——直接执行安装，
        // 不必加载主空间列表（装完 refreshBoxFlag 自然会刷），也不弹「选择 APK」。
        if (getIntent() != null && getIntent().hasExtra(MainActivity.EXTRA_INSTALL_URI)) {
            final String uriStr = getIntent().getStringExtra(MainActivity.EXTRA_INSTALL_URI);
            getIntent().removeExtra(MainActivity.EXTRA_INSTALL_URI);
            if (uriStr != null) {
                mList.post(new Runnable() {
                    @Override
                    public void run() {
                        installFromUri(Uri.parse(uriStr));
                    }
                });
                return;
            }
        }
        reloadHostApps();
        // 从主页「装 APK」按钮进来时，直接把文件选择器拉起来
        if (getIntent() != null && getIntent().getBooleanExtra(MainActivity.EXTRA_AUTO_PICK, false)) {
            getIntent().removeExtra(MainActivity.EXTRA_AUTO_PICK);
            mList.post(new Runnable() {
                @Override
                public void run() {
                    pickApk();
                }
            });
        }
    }

    private void pickApk() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // TwinBox 2.0.8：原来写死 application/vnd.android.package-archive，
            // 部分 OEM 文件选择器对这个 MIME 返回空。放宽到 */*，选完自己判是不是 APK。
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    /** 后台一次性拉主空间应用列表，避免首屏在主线程串行打 binder */
    private void reloadHostApps() {
        mProgressWrap.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        mProgressText.setText(R.string.progress_listing);
        new ListHostTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // TwinBox 2.1.60：进页面即挂任务中心监听——后台跑的任务进度
        // 实时反映到按钮；有任务完成时顺手刷新容器标记。
        mCenterListener = new InstallCenter.Listener() {
            @Override
            public void onTaskUpdate(InstallCenter.Task task) {
                updateTaskButton();
                if (InstallCenter.get().hasRecentlyFinished()) {
                    refreshBoxFlag();
                }
            }
        };
        InstallCenter.get().addListener(mCenterListener);
        updateTaskButton();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mCenterListener != null) {
            InstallCenter.get().removeListener(mCenterListener);
            mCenterListener = null;
        }
    }

    /** TwinBox 2.1.60：任务按钮文案——空闲「任务」；有活动任务显示百分比+计数 */
    private void updateTaskButton() {
        java.util.List<InstallCenter.Task> ts = InstallCenter.get().snapshot();
        if (ts.isEmpty()) {
            mBtnTasks.setText(R.string.btn_tasks_idle);
            return;
        }
        int active = 0;
        String topState = "";
        for (InstallCenter.Task task : ts) {
            if (task.state == InstallCenter.STATE_RUNNING) {
                active++;
                if (task.progress >= 0) {
                    topState = task.progress + "%";
                } else if (topState.isEmpty()) {
                    topState = "…";
                }
            }
        }
        if (active == 0) {
            topState = getString(R.string.btn_tasks_done);
        }
        mBtnTasks.setText(getString(R.string.btn_tasks_fmt, topState, ts.size()));
    }

    /**
     * TwinBox 2.1.60：任务面板（小列表）。
     * 行内：类型标签 + 应用名 + 精度进度条 + 状态。
     * 顶部「选择 APK 安装」收编原按钮功能。
     */
    private void showTasksDialog() {
        java.util.List<InstallCenter.Task> ts = InstallCenter.get().snapshot();
        if (ts.isEmpty()) {
            // 空任务面板仍要能装 APK
            pickApk();
            return;
        }
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);

        TextView pickRow = new TextView(this);
        pickRow.setText(R.string.btn_tasks_pick_apk);
        pickRow.setTextColor(0xFF5B8CFF);
        pickRow.setTextSize(15);
        pickRow.setPadding(0, pad / 2, 0, pad);
        pickRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        root.addView(pickRow);

        for (final InstallCenter.Task task : ts) {
            root.addView(buildTaskRow(task, pad));
        }

        TextView clear = new TextView(this);
        clear.setText(R.string.btn_tasks_clear);
        clear.setTextColor(0xFF8A93A8);
        clear.setTextSize(13);
        clear.setPadding(0, pad, 0, 0);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InstallCenter.get().clearFinished();
                updateTaskButton();
            }
        });
        root.addView(clear);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_tasks_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private View buildTaskRow(final InstallCenter.Task task, int padPx) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, padPx / 2, 0, padPx / 2);

        TextView kind = new TextView(this);
        kind.setTextSize(18);
        kind.setTextColor(0xFF5B8CFF);
        kind.setText(task.type == InstallCenter.TYPE_CLONE ? "克"
                : task.type == InstallCenter.TYPE_MULTI ? "多" : "装");
        row.addView(kind);

        android.widget.LinearLayout mid = new android.widget.LinearLayout(this);
        mid.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams midLp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = padPx / 2;
        mid.setLayoutParams(midLp);

        TextView name = new TextView(this);
        name.setText(task.label);
        name.setTextColor(0xFFF2F4FA);
        name.setTextSize(14);
        mid.addView(name);

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF8A93A8);
        status.setText(InstallCenter.get().describeState(task));
        mid.addView(status);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        if (task.progress >= 0) {
            pb.setProgress(task.progress);
        } else {
            pb.setIndeterminate(task.state == InstallCenter.STATE_RUNNING);
        }
        android.widget.LinearLayout.LayoutParams pbLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * 4));
        pbLp.topMargin = padPx / 3;
        pb.setLayoutParams(pbLp);
        if (task.state == InstallCenter.STATE_RUNNING) {
            mid.addView(pb);
        }
        row.addView(mid);
        return row;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installFromUri(data.getData());
        }
    }

    private void installFromUri(final Uri uri) {
        // TwinBox 2.1.60：任务上移 InstallCenter——退出页面进度不丢，
        // 结果在任务面板查看（失败原因也在面板 + TLog）。
        String name = queryName(uri);
        InstallCenter.get().submitApk(InstallActivity.this, uri, name);
        updateTaskButton();
    }

    /** 装完/多开后只需重新取一次「容器内已装集合」，不用重建整个列表 */
    private void refreshBoxFlag() {
        mProgressWrap.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        mProgressText.setText(R.string.progress_listing);
        new ListHostTask(true).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable t) {
        }
        return "APK";
    }

    /**
     * 后台任务：拉主空间应用 + 容器内已装集合。
     *
     * @param keepData true 时只刷新集合，保留已有数据（安装/多开完成后调用）
     */
    private class ListHostTask extends AsyncTask<Void, Void, List<VBox.VAppEntry>> {
        private final boolean keepData;
        private final Set<String> boxPkgs = new HashSet<String>();

        ListHostTask() {
            this(false);
        }

        ListHostTask(boolean keepData) {
            this.keepData = keepData;
        }

        @Override
        protected List<VBox.VAppEntry> doInBackground(Void... voids) {
            // 容器内已装包名集合：一次 IPC 拿到，替代原来每行一次 IPC
            try {
                List<VBox.VAppEntry> box = VBox.listInstalled();
                if (box != null) {
                    for (VBox.VAppEntry e : box) {
                        if (e.packageName != null) {
                            boxPkgs.add(e.packageName);
                        }
                    }
                }
            } catch (Throwable t) {
                TLog.w("Install", "listInstalled for box flag fail: " + t);
            }
            if (keepData && mAdapter != null && mAdapter.size() > 0) {
                return new ArrayList<VBox.VAppEntry>(mAdapter.snapshot());
            }
            try {
                return VBox.listHostApps();
            } catch (Throwable t) {
                TLog.e("Install", "listHostApps fail", t);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<VBox.VAppEntry> apps) {
            if (isFinishing()) {
                return;
            }
            mProgressWrap.setVisibility(View.GONE);
            if (apps == null) {
                Toast.makeText(InstallActivity.this, "读取主空间应用失败，请回上层重试",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (mAdapter == null) {
                mAdapter = new HostAppAdapter(apps, boxPkgs);
                mList.setAdapter(mAdapter);
            } else {
                // TwinBox 2.0.8：复用 adapter，别每次 new，滚动位置要留住
                mAdapter.swap(apps, boxPkgs);
            }
        }
    }

    private class HostAppAdapter extends BaseAdapter {
        private final List<VBox.VAppEntry> data = new ArrayList<VBox.VAppEntry>();
        private final Set<String> boxPkgs = new HashSet<String>();

        HostAppAdapter(List<VBox.VAppEntry> d, Set<String> box) {
            data.addAll(d);
            if (box != null) {
                boxPkgs.addAll(box);
            }
        }

        List<VBox.VAppEntry> snapshot() {
            return new ArrayList<VBox.VAppEntry>(data);
        }

        int size() {
            return data.size();
        }

        void swap(List<VBox.VAppEntry> d, Set<String> box) {
            data.clear();
            if (d != null) {
                data.addAll(d);
            }
            boxPkgs.clear();
            if (box != null) {
                boxPkgs.addAll(box);
            }
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

        private boolean inBox(String pkg) {
            return pkg != null && boxPkgs.contains(pkg);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_install_app, parent, false);
            }
            final VBox.VAppEntry e = getItem(position);
            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView label = (TextView) v.findViewById(R.id.label);
            TextView pkg = (TextView) v.findViewById(R.id.pkg);
            TextView action = (TextView) v.findViewById(R.id.action);
            icon.setImageDrawable(e.icon == null
                    ? getResources().getDrawable(android.R.drawable.sym_def_app_icon) : e.icon);
            label.setText(e.label == null ? e.packageName : e.label);
            pkg.setText(e.packageName);
            boolean has = inBox(e.packageName);
            action.setText(has
                    ? getString(R.string.cloned_tag) + " · 点按多开"
                    : getString(R.string.action_clone));
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (inBox(e.packageName)) {
                        // TwinBox 2.1.60：多开也进任务中心（大包提取 lib 同样耗时）
                        InstallCenter.get().submitMulti(e.packageName, e.label);
                    } else {
                        InstallCenter.get().submitClone(e.packageName, e.label);
                    }
                    updateTaskButton();
                }
            });
            return v;
        }
    }

    private class CloneTask extends AsyncTask<Void, Void, String> {
        private final VBox.VAppEntry e;

        CloneTask(VBox.VAppEntry e) {
            this.e = e;
        }

        @Override
        protected void onPreExecute() {
            TLog.i("Install", "clone task begin: " + e.label);
            mProgressWrap.setVisibility(View.VISIBLE);
            mProgress.setIndeterminate(true);
            mProgressText.setText("正在克隆 " + e.label + " …");
        }

        @Override
        protected String doInBackground(Void... voids) {
            InstallResult r = VBox.cloneHostApp(e.packageName);
            if (r == null) {
                return VBox.isInstalledInBox(e.packageName) ? "已在容器中" : "克隆失败(见日志)";
            }
            return r.isSuccess ? "克隆成功：" + e.label : "克隆失败：" + String.valueOf(r.error);
        }

        @Override
        protected void onPostExecute(String s) {
            mProgressWrap.setVisibility(View.GONE);
            Toast.makeText(InstallActivity.this, s, Toast.LENGTH_SHORT).show();
            refreshBoxFlag();
        }
    }

}
