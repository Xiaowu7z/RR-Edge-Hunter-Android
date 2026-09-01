package com.cf.ip.better;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class RTTResult implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public final native String getIP();

    public final native long getLatencyMs();

    public final native void setIP(String str);

    public final native void setLatencyMs(long j);

    static {
        Better.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    RTTResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public RTTResult() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RTTResult)) {
            return false;
        }
        RTTResult rTTResult = (RTTResult) obj;
        String ip = getIP();
        String ip2 = rTTResult.getIP();
        if (ip == null) {
            if (ip2 != null) {
                return false;
            }
        } else if (!ip.equals(ip2)) {
            return false;
        }
        return getLatencyMs() == rTTResult.getLatencyMs();
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getIP(), Long.valueOf(getLatencyMs())});
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("RTTResult{IP:");
        sb.append(getIP()).append(",LatencyMs:");
        sb.append(getLatencyMs()).append(",}");
        return sb.toString();
    }
}
