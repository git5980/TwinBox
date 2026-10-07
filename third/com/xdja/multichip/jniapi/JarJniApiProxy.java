package com.xdja.multichip.jniapi;

/** xdja 安全芯片桩：所有操作返回失败码，调用方走 catch 分支。 */
public class JarJniApiProxy {
    public int getCardType() { return -1; }
    public int VerifyPIN(int role, byte[] pin, int len) { return -1; }
    public int GetPinTryCount(int role) { return -1; }
    public int GenRandom(int len, byte[] out) { return -1; }
    public int SM1(byte[] key, int keylen, int mode, byte[] seckey, byte kid, Object pad) { return -1; }
}
