package dev.twinbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.os.VEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TwinBox 2.1.58：容器内文件管理器。
 *
 * 定位：宿主进程直接浏览/操作容器目录树（分身看到的「SD 卡」与应用数据），
 * 是检查容器内容的官方通道——外部文件管理器（MT 之类）看到的是宿主私有
 * 目录，只有本管理器懂容器视图的对应关系。
 *
 * 安全立场：
 *  - 只操作容器目录树（VEnvironment 的容器根），不触真实 /sdcard；
 *  - 不提供「外部打开」——文件一律不出容器（数据不出容器主线的延续）；
 *  - 删除/移动均为容器内部操作。
 *
 * v1 能力：浏览（SD 卡视图 / 应用数据视图）、新建文件夹、重命名、
 * 复制/剪切/粘贴、递归删除、三种排序、详情。文件预览与搜索排期下版。
 */
public class FileExplorerActivity extends Activity implements AdapterView.OnItemClickListener,
        AdapterView.OnItemLongClickListener {

    private static final String TAG = "V|FM";

    /** 跨界面剪贴板（进程级；离开应用即失效，符合最小驻留） */
    private static File sClip;
    private static boolean sClipCut;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_TIME = 2;

    private ListView mList;
    private TextView mPathBar;
    private TextView mInfo;
    private FileAdapter mAdapter;

    private int mUserId = 0;
    private boolean mInData = false;
    private int mSort = SORT_NAME;
    private File mRoot;
    private File mCwd;
    private final List<File> mEntries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        mList = (ListView) findViewById(R.id.fm_list);
        mPathBar = (TextView) findViewById(R.id.fm_path_bar);
        mInfo = (TextView) findViewById(R.id.fm_info);
        mAdapter = new FileAdapter();
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener(this);
        mList.setOnItemLongClickListener(this);
        mList.setEmptyView(findViewById(android.R.id.empty));
        // 默认进分身 0 的 SD 卡视图；多分身用菜单「选择分身」切换
        enterRoot(false);
    }

    // ---------- 导航 ----------

    private void enterRoot(boolean data) {
        mInData = data;
        mRoot = data ? VEnvironment.getUserDataDirectory(mUserId)
                : VEnvironment.getExternalStorageDirectory(mUserId);
        enter(mRoot);
    }

    private void enter(File dir) {
        mCwd = dir;
        refresh();
    }

    private void refresh() {
        File[] arr = mCwd.listFiles();
        mEntries.clear();
        if (arr != null) {
            mEntries.addAll(Arrays.asList(arr));
        }
        Collections.sort(mEntries, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                int r;
                if (mSort == SORT_SIZE) {
                    r = Long.compare(a.length(), b.length());
                } else if (mSort == SORT_TIME) {
                    r = Long.compare(a.lastModified(), b.lastModified());
                } else {
                    r = a.getName().toLowerCase(Locale.US)
                            .compareTo(b.getName().toLowerCase(Locale.US));
                }
                return r;
            }
        });
        int dirs = 0;
        for (File f : mEntries) {
            if (f.isDirectory()) {
                dirs++;
            }
        }
        String rel = mRoot.equals(mCwd) ? "/"
                : "/" + mRoot.getName() + mCwd.getAbsolutePath().substring(mRoot.getAbsolutePath().length());
        mPathBar.setText((mInData ? "应用数据 · " : "SD 卡 · ") + rel);
        mInfo.setText(getString(R.string.title_files) + " · " + mEntries.size()
                + " 项（" + dirs + " 文件夹 / " + (mEntries.size() - dirs) + " 文件）");
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        if (mCwd != null && !mCwd.equals(mRoot) && mCwd.getParentFile() != null) {
            enter(mCwd.getParentFile());
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 菜单 ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "选择分身");
        menu.add(0, 2, 1, mInData ? "切换到 SD 卡" : "切换到应用数据");
        menu.add(0, 3, 2, "新建文件夹");
        menu.add(0, 4, 3, "粘贴" + (sClip == null ? "" : "（" + sClip.getName() + (sClipCut ? " ✂" : " ⧉") + "）"));
        menu.add(0, 5, 4, "排序（名称/大小/时间）");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: {
                pickUser();
                return true;
            }
            case 2: {
                enterRoot(!mInData);
                return true;
            }
            case 3: {
                promptMkdir();
                return true;
            }
            case 4: {
                paste();
                return true;
            }
            case 5: {
                new AlertDialog.Builder(this)
                        .setTitle("排序")
                        .setItems(new CharSequence[]{"按名称", "按大小", "按时间"},
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mSort = which;
                                        refresh();
                                    }
                                })
                        .show();
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void pickUser() {
        // 从已装分身枚举虚拟用户（引擎侧的用户号才是容器视图的真实分区）
        List<Integer> users = new ArrayList<>();
        try {
            List<VBox.VAppEntry> apps = VBox.listInstalled();
            if (apps != null) {
                for (VBox.VAppEntry e : apps) {
                    if (e.users != null) {
                        for (int u : e.users) {
                            if (!users.contains(u)) {
                                users.add(u);
                            }
                        }
                    } else if (!users.contains(e.userId)) {
                        users.add(e.userId);
                    }
                }
            }
        } catch (Throwable t) {
            TLog.w(TAG, "listInstalled fail: " + t);
        }
        if (users.isEmpty()) {
            users.add(0);
        }
        Collections.sort(users);
        String[] names = new String[users.size()];
        for (int i = 0; i < users.size(); i++) {
            names[i] = "分身 #" + users.get(i);
        }
        final List<Integer> finalUsers = users;
        new AlertDialog.Builder(this)
                .setTitle("选择分身")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mUserId = finalUsers.get(which);
                        enterRoot(mInData);
                    }
                })
                .show();
    }

    // ---------- 操作 ----------

    private void promptMkdir() {
        final EditText input = new EditText(this);
        input.setHint("文件夹名称");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹（" + mCwd.getName() + "）")
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        File f = new File(mCwd, name);
                        if (f.mkdirs() || f.isDirectory()) {
                            TLog.i(TAG, "mkdir " + f);
                        } else {
                            toast("创建失败");
                        }
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptRename(final File f) {
        final EditText input = new EditText(this);
        input.setText(f.getName());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty() || name.equals(f.getName())) {
                            return;
                        }
                        File dst = new File(f.getParentFile(), name);
                        if (f.renameTo(dst)) {
                            TLog.i(TAG, "rename " + f.getName() + " -> " + name);
                        } else {
                            toast("重命名失败");
                        }
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptDelete(final File f) {
        String msg = f.isDirectory() ? "将递归删除整个文件夹（含全部内容）" : "将删除该文件";
        new AlertDialog.Builder(this)
                .setTitle("删除 " + f.getName() + "？")
                .setMessage(msg + "\n此操作不可撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runOp("删除 " + f.getName(), new Op() {
                            @Override
                            public void run() throws IOException {
                                deleteRecursive(f);
                                TLog.i(TAG, "delete " + f);
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void paste() {
        if (sClip == null || !sClip.exists()) {
            toast("剪贴板为空");
            sClip = null;
            return;
        }
        if (mCwd.equals(sClip) || mCwd.getAbsolutePath().startsWith(sClip.getAbsolutePath() + "/")) {
            toast("不能粘贴到自身内部");
            return;
        }
        final File src = sClip;
        final boolean cut = sClipCut;
        runOp((cut ? "移动 " : "复制 ") + src.getName(), new Op() {
            @Override
            public void run() throws IOException {
                if (cut) {
                    File dst = uniqueTarget(mCwd, src.getName());
                    if (src.renameTo(dst)) {
                        TLog.i(TAG, "move " + src + " -> " + dst);
                    } else {
                        // 跨视图根（SD 卡 ↔ 应用数据）rename 可能失败，退化为复制+删除
                        copyRecursive(src, dst);
                        deleteRecursive(src);
                        TLog.i(TAG, "move(cascade) " + src + " -> " + dst);
                    }
                } else {
                    File dst = uniqueTarget(mCwd, src.getName());
                    copyRecursive(src, dst);
                    TLog.i(TAG, "copy " + src + " -> " + dst);
                }
            }
        });
        if (cut) {
            sClip = null;
            sClipCut = false;
        }
    }

    /** 同名冲突自动编号（名称 (2)、名称 (3)…） */
    private static File uniqueTarget(File dir, String name) {
        File dst = new File(dir, name);
        if (!dst.exists()) {
            return dst;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int n = 2;
        while (dst.exists()) {
            dst = new File(dir, base + " (" + n + ")" + ext);
            n++;
        }
        return dst;
    }

    private interface Op {
        void run() throws IOException;
    }

    private void runOp(String title, final Op op) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setMessage("请稍候…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    op.run();
                } catch (final IOException e) {
                    TLog.e(TAG, "op fail", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("操作失败：" + e.getMessage());
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            refresh();
                        }
                    });
                }
            }
        }).start();
    }

    private static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] arr = f.listFiles();
            if (arr != null) {
                for (File c : arr) {
                    deleteRecursive(c);
                }
            }
        }
        if (!f.delete() && f.exists()) {
            throw new IOException("无法删除 " + f.getName());
        }
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建 " + dst.getName());
            }
            File[] arr = src.listFiles();
            if (arr != null) {
                for (File c : arr) {
                    copyRecursive(c, new File(dst, c.getName()));
                }
            }
            return;
        }
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 列表交互 ----------

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        File f = mEntries.get(position);
        if (f.isDirectory()) {
            enter(f);
        } else {
            showDetail(f);
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
        final File f = mEntries.get(position);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(new CharSequence[]{"复制", "剪切", "重命名", "删除", "详情"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                switch (which) {
                                    case 0:
                                        sClip = f;
                                        sClipCut = false;
                                        toast("已复制（容器内粘贴）");
                                        break;
                                    case 1:
                                        sClip = f;
                                        sClipCut = true;
                                        toast("已剪切（容器内粘贴）");
                                        break;
                                    case 2:
                                        promptRename(f);
                                        break;
                                    case 3:
                                        promptDelete(f);
                                        break;
                                    default:
                                        showDetail(f);
                                        break;
                                }
                            }
                        })
                .show();
        return true;
    }

    private void showDetail(File f) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("路径：").append(f.getAbsolutePath()).append('\n');
        sb.append("大小：").append(f.isDirectory() ? "（文件夹）" : Formatter.formatFileSize(this, f.length()));
        if (!f.isDirectory()) {
            sb.append("（").append(f.length()).append(" 字节）");
        }
        sb.append('\n');
        sb.append("修改时间：").append(fmt.format(new Date(f.lastModified()))).append('\n');
        sb.append("读写：").append(f.canRead() ? "可读 " : "不可读 ").append(f.canWrite() ? "可写" : "不可写");
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------- 适配器 ----------

    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public File getItem(int position) {
            return mEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            }
            File f = getItem(position);
            TextView icon = (TextView) v.findViewById(R.id.fm_item_icon);
            TextView name = (TextView) v.findViewById(R.id.fm_item_name);
            TextView sub = (TextView) v.findViewById(R.id.fm_item_sub);
            icon.setText(f.isDirectory() ? "▣" : "◧");
            name.setText(f.getName());
            if (f.isDirectory()) {
                sub.setText("文件夹");
            } else {
                sub.setText(Formatter.formatFileSize(FileExplorerActivity.this, f.length())
                        + " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(new Date(f.lastModified())));
            }
            return v;
        }
    }
}
