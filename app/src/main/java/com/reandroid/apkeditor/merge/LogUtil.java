package com.reandroid.apkeditor.merge;

public class LogUtil {
    private static Merger.LogListener logListener;

    private static boolean logEnabled;

    public static void setLogListener(Merger.LogListener listener) {
        logListener = listener;
    }

    public static void setLogEnabled(boolean enabled) {
        logEnabled = enabled;
    }

    public static void logMessage(String msg) {
        if (logListener != null && logEnabled) {
            logListener.onLog(msg);
        }
    }
}
