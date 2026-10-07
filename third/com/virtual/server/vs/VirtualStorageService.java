package com.lody.virtual.server.vs;

import android.util.SparseArray;

import com.lody.virtual.server.interfaces.IVirtualStorageService;
import com.lody.virtual.server.pm.VUserManagerService;

import java.util.HashMap;

/**
 * @author Lody
 */

public class VirtualStorageService extends IVirtualStorageService.Stub {

    private static final VirtualStorageService sService = new VirtualStorageService();
    private final VSPersistenceLayer mLayer = new VSPersistenceLayer(this);
    private final SparseArray<HashMap<String, VSConfig>> mConfigs = new SparseArray<>();

    public static VirtualStorageService get() {
        return sService;
    }

    private VirtualStorageService() {
        mLayer.read();
    }

    SparseArray<HashMap<String, VSConfig>> getConfigs() {
        return mConfigs;
    }

    @Override
    public void setVirtualStorage(String packageName, int userId, String vsPath) {
        checkUserId(userId);
        synchronized (mConfigs) {
            VSConfig config = getOrCreateVSConfigLocked(packageName, userId);
            config.vsPath = vsPath;
            mLayer.save();
        }
    }

    private VSConfig getOrCreateVSConfigLocked(String packageName, int userId) {
        HashMap<String, VSConfig> userMap = mConfigs.get(userId);
        if (userMap == null) {
            userMap = new HashMap<>();
            mConfigs.put(userId, userMap);
        }
        VSConfig config = userMap.get(packageName);
        if (config == null) {
            config = new VSConfig();
            // TwinBox 2.1.29：虚拟存储默认关闭。
            // 原默认 true + .so（IO 重定向）装上前一直空转没人发现；.so 真正生效后
            // 每个 guest 的 /sdcard、/storage/emulated/0 等挂载点都被换成空的
            // per-app 虚拟目录 —— MediaStore 还能列出歌（走系统 provider），
            // 真正按路径开文件时落到空目录，表现就是「音乐播放没有声音」。
            // 需要隔离存储的场景再显式打开。
            config.enable = false;
            userMap.put(packageName, config);
        }
        return config;
    }


    @Override
    public String getVirtualStorage(String packageName, int userId) {
        checkUserId(userId);
        synchronized (mConfigs) {
            VSConfig config = getOrCreateVSConfigLocked(packageName, userId);
            return config.vsPath;
        }
    }

    @Override
    public void setVirtualStorageState(String packageName, int userId, boolean enable) {
        checkUserId(userId);
        synchronized (mConfigs) {
            VSConfig config = getOrCreateVSConfigLocked(packageName, userId);
            config.enable = enable;
            mLayer.save();
        }

    }

    @Override
    public boolean isVirtualStorageEnable(String packageName, int userId) {
        checkUserId(userId);
        synchronized (mConfigs) {
            VSConfig config = getOrCreateVSConfigLocked(packageName, userId);
            return config.enable;
        }

    }

    private void checkUserId(int userId) {
        if (!VUserManagerService.get().exists(userId)) {
            throw new IllegalStateException("Invalid userId " + userId);
        }
    }
}
