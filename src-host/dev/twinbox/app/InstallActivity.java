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

    private static final int REQ_PICK_APK = 41;

    private ListView mList;
    private View mProgressWrap;
    private ProgressBar mProgress;
    private TextView mProgressText;
    private HostAppAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        mList = (ListView) findViewById(R.id.list);
        mProgressWrap = findViewById(R.id.progress_wrap);
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mProgressText = (TextView) findViewById(R.id.progress_text);
        findViewById(R.id.btn_pick_apk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
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
        new ListHostTask().execute();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installFromUri(data.getData());
        }
    }

    private void installFromUri(final Uri uri) {
        mProgressWrap.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        String name = queryName(uri);
        mProgressText.setText("正在安装 " + name + " …");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                TLog.i("Install", "installFromUri: " + uri);
                File tmp = new File(getCacheDir(), "incoming.apk");
                try {
                    TLog.i("Install", "copying to cache...");
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        return "读取失败：打不开所选文件";
                    }
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.close();
                    in.close();
                } catch (Throwable t) {
                    TLog.e("Install", "copy to cache fail", t);
                    return "读取失败：" + t.getMessage();
                }
                TLog.i("Install", "copied apk size=" + tmp.length() + " bytes");
                if (tmp.length() == 0) {
                    return "读取失败：文件为空（选中的可能不是 APK）";
                }
                InstallResult r = VBox.installApk(InstallActivity.this, tmp.getAbsolutePath());
                if (r == null) {
                    return "安装失败";
                }
                if (r.isSuccess) {
                    return "安装成功：" + (r.packageName == null ? name : r.packageName);
                }
                return "安装失败：" + (r.error == null ? "未知错误" : String.valueOf(r.error));
            }

            @Override
            protected void onPostExecute(String s) {
                mProgressWrap.setVisibility(View.GONE);
                Toast.makeText(InstallActivity.this, s, Toast.LENGTH_LONG).show();
                refreshBoxFlag();
            }
        }.execute();
    }

    /** 装完/多开后只需重新取一次「容器内已装集合」，不用重建整个列表 */
    private void refreshBoxFlag() {
        mProgressWrap.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        mProgressText.setText(R.string.progress_listing);
        new ListHostTask(true).execute();
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
                        new MultiOpenTask(e).execute();
                    } else {
                        new CloneTask(e).execute();
                    }
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

    /** 已克隆应用再点一次 → 多开一个新分身 */
    private class MultiOpenTask extends AsyncTask<Void, Void, String> {
        private final VBox.VAppEntry e;

        MultiOpenTask(VBox.VAppEntry e) {
            this.e = e;
        }

        @Override
        protected void onPreExecute() {
            mProgressWrap.setVisibility(View.VISIBLE);
            mProgress.setIndeterminate(true);
            mProgressText.setText("正在多开 " + e.label + " …");
        }

        @Override
        protected String doInBackground(Void... voids) {
            int uid = VBox.cloneToNewUser(e.packageName);
            return uid >= 0 ? "分身已创建（#" + uid + "）" : "多开失败";
        }

        @Override
        protected void onPostExecute(String s) {
            mProgressWrap.setVisibility(View.GONE);
            Toast.makeText(InstallActivity.this, s, Toast.LENGTH_SHORT).show();
            refreshBoxFlag();
        }
    }
}
