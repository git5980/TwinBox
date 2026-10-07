package com.xdja.multichip.param;

/** xdja jar_multi_jniapi 桩：仅满足编译，运行时不可用（initSafekeyLib 会安全退出）。 */
public class JniApiParam {
    public static final int TYPE_TF = 0;
    public static final int TYPE_COVERED = 1;
    public static final int TYPE_VHSM = 2;
    public static final int TYPE_VHSM_NET = 3;
    public static final int TYPE_ONBOARD = 4;
    public static final int TYPE_BLUETOOTH = 5;
    public String cardId;
    public int chipType;
}
