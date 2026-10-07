package com.xdja.multichip.jniapi;

import android.content.Context;
import android.util.Pair;
import com.xdja.multichip.param.JniApiParam;
import java.util.List;

/** xdja 芯片管家桩：getInstance 返回 null，initSafekeyLib 判空即安全退出。 */
public class JarMultiJniApiManager {
    public static JarMultiJniApiManager getInstance() { return null; }
    public Pair<Integer, List<JniApiParam>> getAll(Context ctx) { return null; }
    public Pair<Integer, JarJniApiProxy> make(Context ctx, String cardId) { return null; }
}
