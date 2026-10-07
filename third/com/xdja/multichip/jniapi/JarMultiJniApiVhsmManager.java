package com.xdja.multichip.jniapi;

/** xdja VHSM 桩。 */
public class JarMultiJniApiVhsmManager {
    private static JarMultiJniApiVhsmManager sInstance = new JarMultiJniApiVhsmManager();
    public static JarMultiJniApiVhsmManager getInstance() { return sInstance; }
    public int SM4(JarJniApiProxy proxy, byte[] key, int keylen, int mode, byte[] seckey, byte kid, Object pad) { return -1; }
}
