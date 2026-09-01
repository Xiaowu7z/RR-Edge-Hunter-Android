package com.cf.ip.better;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class ScanResult implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public final native long getBandwidth();

    public final native String getDataCenter();

    public final native long getElapsed();

    public final native String getError();

    public final native String getIP();

    public final native long getLatencyMs();

    public final native long getMaxSpeed();

    public final native long getRealBandwidth();

    public final native void setBandwidth(long j);

    public final native void setDataCenter(String str);

    public final native void setElapsed(long j);

    public final native void setError(String str);

    public final native void setIP(String str);

    public final native void setLatencyMs(long j);

    public final native void setMaxSpeed(long j);

    public final native void setRealBandwidth(long j);

    static {
        Better.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    ScanResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public ScanResult() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ScanResult)) {
            return false;
        }
        ScanResult scanResult = (ScanResult) obj;
        String ip = getIP();
        String ip2 = scanResult.getIP();
        if (ip == null) {
            if (ip2 != null) {
                return false;
            }
        } else if (!ip.equals(ip2)) {
            return false;
        }
        if (getBandwidth() != scanResult.getBandwidth() || getRealBandwidth() != scanResult.getRealBandwidth() || getMaxSpeed() != scanResult.getMaxSpeed() || getLatencyMs() != scanResult.getLatencyMs()) {
            return false;
        }
        String dataCenter = getDataCenter();
        String dataCenter2 = scanResult.getDataCenter();
        if (dataCenter == null) {
            if (dataCenter2 != null) {
                return false;
            }
        } else if (!dataCenter.equals(dataCenter2)) {
            return false;
        }
        if (getElapsed() != scanResult.getElapsed()) {
            return false;
        }
        String error = getError();
        String error2 = scanResult.getError();
        if (error == null) {
            return error2 == null;
        }
        return error.equals(error2);
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getIP(), Long.valueOf(getBandwidth()), Long.valueOf(getRealBandwidth()), Long.valueOf(getMaxSpeed()), Long.valueOf(getLatencyMs()), getDataCenter(), Long.valueOf(getElapsed()), getError()});
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("ScanResult{IP:");
        sb.append(getIP()).append(",Bandwidth:");
        sb.append(getBandwidth()).append(",RealBandwidth:");
        sb.append(getRealBandwidth()).append(",MaxSpeed:");
        sb.append(getMaxSpeed()).append(",LatencyMs:");
        sb.append(getLatencyMs()).append(",DataCenter:");
        sb.append(getDataCenter()).append(",Elapsed:");
        sb.append(getElapsed()).append(",Error:");
        sb.append(getError()).append(",}");
        return sb.toString();
    }
}
