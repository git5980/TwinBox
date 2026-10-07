package com.lody.virtual.client.hook.proxies.media.scanner;

import com.lody.virtual.client.hook.annotations.Inject;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;

import mirror.android.media.IMediaScannerService;

/**
 * TwinBox 2.1.57：media_scanner 服务接管（数据不出容器防线之一）。
 *
 * MediaScannerConnection.scanFile() 的 binder 通道：guest 拿到的
 * IMediaScannerService 经 ServiceManager 缓存注入本代理，请求路径在
 * 墙外（真 sdcard）时被吞掉并留痕；墙内（容器目录树）放行。
 * 与 LocaleManagerStub（2.1.43）/ AlarmManagerStub 同构。
 */
@Inject(MethodProxies.class)
public class MediaScannerStub extends BinderInvocationProxy {

    public MediaScannerStub() {
        super(IMediaScannerService.Stub.asInterface, "media_scanner");
    }
}
