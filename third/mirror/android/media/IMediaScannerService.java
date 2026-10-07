package mirror.android.media;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/**
 * TwinBox 2.1.57：android.media.IMediaScannerService（隐藏服务 "media_scanner"）。
 *
 * AOSP 签名（各版本稳定）：
 *   void scanFile(String path, String mimeType, IMediaScannerListener listener);
 *   void requestScanFile(String path, String mimeType, IMediaScannerListener listener);
 * （批量 scanFile(String[]...) 是 hiding/removed，不依赖。）
 *
 * 用途：guest 请求系统扫描某个路径（把它收进 MediaStore/相册）。容器内数据
 * 路径放行（宿主私有目录，扫了也进不了相册）；真 sdcard 路径吞掉并留痕——
 * 防止 guest 把敏感文件主动推进真系统索引。
 */
public class IMediaScannerService {
    public static Class<?> TYPE = RefClass.load(IMediaScannerService.class, "android.media.IMediaScannerService");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(Stub.class, "android.media.IMediaScannerService$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
