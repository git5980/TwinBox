package dev.twinbox.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 文件中转：宿主分享文件 → 存入容器共享目录（VMOS 文件中转站同款）。
 *
 * TwinBox 2.0.8：
 *
 *  1) 分享 APK 时 InstallActivity 与本页的 intent-filter 会同时命中
 *     （InstallActivity 是 apk mime + content/file scheme，本页是 ACTION_SEND 通配所有类型），
 *     系统每次都会弹「用什么打开」。收到 APK 时直接显式转给 InstallActivity，
 *     把选择歧义消掉。
 *
 *  2) 文件名的扩展名与真实名：原来只用 uri.getLastPathSegment() 切扩展名，
 *     content://…/document/primary:Download/a.apk 这种会切出一长串 documentId。
 *     现在优先 OpenableColumns.DISPLAY_NAME。
 */
public class BridgeActivity extends Activity {

    private static final String[] APK_MIMES = {
            "application/vnd.android.package-archive",
            "application/x-android-package",
            "application/java-archive",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String action = intent.getAction();
        Uri uri = intent.getData() == null && Intent.ACTION_SEND.equals(action)
                ? (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM)
                : intent.getData();
        if (uri == null) {
            finish();
            return;
        }
        if (isApk(intent)) {
            // APK 交给安装中心，让用户在熟悉的界面里看到结果
            TLog.i("Bridge", "apk shared -> forward to InstallActivity: " + uri);
            Intent i = new Intent(this, InstallActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.setDataAndType(uri, intent.getType());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(i);
            } catch (Throwable t) {
                TLog.e("Bridge", "forward to InstallActivity fail", t);
                Toast.makeText(this, "打开安装页失败：" + t.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        saveIntoShared(uri);
    }

    private boolean isApk(Intent intent) {
        String type = intent.getType();
        if (type != null) {
            for (String m : APK_MIMES) {
                if (m.equalsIgnoreCase(type)) {
                    return true;
                }
            }
        }
        // 有些分享端不带正确 MIME，再按扩展名兜一次
        String name = queryName(intent);
        return name != null && name.toLowerCase().endsWith(".apk");
    }

    private String queryName(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) {
            return null;
        }
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable ignore) {
        }
        return uri.getLastPathSegment();
    }

    private void saveIntoShared(Uri uri) {
        File shared = new File(getExternalFilesDir(null), "SharedIn");
        if (!shared.exists()) {
            shared.mkdirs();
        }
        // 优先用 DISPLAY_NAME 作为真实文件名
        String display = queryNameFromUri(uri);
        String name = "shared_" + System.currentTimeMillis();
        if (display != null && display.indexOf('.') >= 0) {
            name = name + display.substring(display.lastIndexOf('.'));
        } else {
            String path = uri.getLastPathSegment();
            if (path != null && path.lastIndexOf('.') >= 0) {
                name = name + path.substring(path.lastIndexOf('.'));
            }
        }
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new Exception("无法读取");
            }
            File out = new File(shared, name);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
            }
            fo.close();
            in.close();
            TLog.i("Bridge", "saved: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
            Toast.makeText(this, "已中转：" + out.getName(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            TLog.e("Bridge", "bridge copy fail", t);
            Toast.makeText(this, "中转失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private String queryNameFromUri(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }
}
