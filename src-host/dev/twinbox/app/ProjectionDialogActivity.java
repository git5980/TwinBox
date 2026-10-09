package dev.twinbox.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * TwinBox 2.2.4：MediaProjection 授权弹窗载体（透明 Activity）。
 *
 * 为什么要它：Service 不能 startActivityForResult；而持有
 * SYSTEM_ALERT_WINDOW（悬浮窗）权限的应用豁免后台 Activity 启动限制
 * （Android 10+ BAL），所以悬浮服务可以直接把这个透明 Activity 拉到
 * 前台，它再弹系统授权窗——用户拖球拉窗时「顺手点一下立即开始」就完成
 * v2 画面授权，不用去主界面找菜单。
 */
public class ProjectionDialogActivity extends Activity {

    private static final int REQ = 7302;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                Toast.makeText(this, "系统不支持屏幕投影", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Throwable t) {
            TLog.e("ProjDlg", "createScreenCaptureIntent fail", t);
            Toast.makeText(this, "无法发起授权", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ) {
            VirtualScreen.onProjectionResult(this, resultCode, data);
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "浮窗画面已授权 ✓ 再拖球拉窗即见真画面",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未授权：窗户将只有窗帘效果",
                        Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
