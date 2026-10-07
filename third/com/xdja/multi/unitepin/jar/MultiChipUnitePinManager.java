package com.xdja.multi.unitepin.jar;

import android.content.Context;
import android.util.Pair;

/** xdja 统一PIN桩。 */
public class MultiChipUnitePinManager {
    private static final MultiChipUnitePinManager sInstance = new MultiChipUnitePinManager();
    public static MultiChipUnitePinManager getInstance() { return sInstance; }
    public Pair<Integer, String> getPin(Context ctx, String cardId, int role) { return null; }
}
