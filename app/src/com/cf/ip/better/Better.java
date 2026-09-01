package com.cf.ip.better;

import go.Seq;

/* JADX INFO: loaded from: classes.dex */
public abstract class Better {
    private static native void _init();

    public static native void cancelScan();

    public static native void clearCache();

    public static native String getIPs(boolean z, boolean z2, long j);

    public static native String getProgress();

    public static native void setCacheDir(String str);

    public static void touch() {
    }

    public static native void updateData();

    static {
        Seq.touch();
        _init();
    }

    private Better() {
    }
}
