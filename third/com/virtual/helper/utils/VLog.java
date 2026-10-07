package com.lody.virtual.helper.utils;

import android.os.Bundle;
import android.util.Log;

import java.util.Set;

import dev.twinbox.app.TLog;

/**
 * @author Lody
 */
public class VLog {

    public static boolean OPEN_LOG = true;

    public static void i(String tag, String msg, Object... format) {
        if (OPEN_LOG) {
            String m = safeFormat(msg, format);
            Log.i(tag, m);
            TLog.i("V|" + tag, m);
        }
    }

    public static void d(String tag, String msg, Object... format) {
        if (OPEN_LOG) {
            String m = safeFormat(msg, format);
            Log.d(tag, m);
            TLog.d("V|" + tag, m);
        }
    }

    public static void logbug(String tag, String msg) {
        d(tag, msg);
    }

    public static void w(String tag, String msg, Object... format) {
        if (OPEN_LOG) {
            String m = safeFormat(msg, format);
            Log.w(tag, m);
            TLog.w("V|" + tag, m);
        }
    }

    public static void e(String tag, String msg) {
        if (OPEN_LOG) {
            Log.e(tag, msg);
            TLog.e("V|" + tag, msg, null);
        }
    }

    private static String safeFormat(String msg, Object... format) {
        try {
            return String.format(msg, format);
        } catch (Throwable t) {
            return msg;
        }
    }

    public static void e(String tag, String msg, Object... format) {
        if (OPEN_LOG) {
            String m = safeFormat(msg, format);
            Log.e(tag, m);
            TLog.e("V|" + tag, m, null);
        }
    }

    public static void v(String tag, String msg) {
        if (OPEN_LOG) {
            Log.v(tag, msg);
            TLog.d("V|" + tag, msg);
        }
    }

    public static void v(String tag, String msg, Object... format) {
        if (OPEN_LOG) {
            String m = safeFormat(msg, format);
            Log.v(tag, m);
            TLog.d("V|" + tag, m);
        }
    }

    public static String toString(Bundle bundle) {
        if (bundle == null) return null;
        if (Reflect.on(bundle).get("mParcelledData") != null) {
            Set<String> keys = bundle.keySet();
            StringBuilder stringBuilder = new StringBuilder("Bundle[");
            if (keys != null) {
                for (String key : keys) {
                    stringBuilder.append(key);
                    stringBuilder.append("=");
                    stringBuilder.append(bundle.get(key));
                    stringBuilder.append(",");
                }
            }
            stringBuilder.append("]");
            return stringBuilder.toString();
        }
        return bundle.toString();
    }

    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    public static void printStackTrace(String tag) {
        Log.e(tag, getStackTraceString(new Exception()));
    }

    public static void e(String tag, Throwable e) {
        Log.e(tag, getStackTraceString(e));
    }
}
