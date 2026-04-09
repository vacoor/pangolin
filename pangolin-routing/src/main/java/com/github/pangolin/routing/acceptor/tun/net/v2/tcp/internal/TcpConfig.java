package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.*;

/**
 * Immutable per-listener TCP configuration.
 * Shared across connections; constructed once at listener startup.
 */
public final class TcpConfig {

    private final int     mss;
    private final int     initialRcvWnd;
    private final int     windowScale;
    private final boolean timestampsEnabled;
    private final boolean windowScalingEnabled;
    private final long    finWait2TimeoutMs;
    private final long    timeWaitMs;
    private final long    delayedAckMs;

    private TcpConfig(Builder b) {
        this.mss                 = b.mss;
        this.initialRcvWnd       = b.initialRcvWnd;
        this.windowScale         = b.windowScale;
        this.timestampsEnabled   = b.timestampsEnabled;
        this.windowScalingEnabled = b.windowScalingEnabled;
        this.finWait2TimeoutMs   = b.finWait2TimeoutMs;
        this.timeWaitMs          = b.timeWaitMs;
        this.delayedAckMs        = b.delayedAckMs;
    }

    public int     mss()                   { return mss; }
    public int     initialRcvWnd()         { return initialRcvWnd; }
    public int     windowScale()           { return windowScale; }
    public boolean timestampsEnabled()     { return timestampsEnabled; }
    public boolean windowScalingEnabled()  { return windowScalingEnabled; }
    public long    finWait2TimeoutMs()     { return finWait2TimeoutMs; }
    public long    timeWaitMs()            { return timeWaitMs; }
    public long    delayedAckMs()          { return delayedAckMs; }

    public static Builder builder()        { return new Builder(); }

    /** Default config for 1500-byte Ethernet MTU. */
    public static final TcpConfig DEFAULT = builder().build();

    public static final class Builder {
        private int     mss                 = TCP_MSS_1460;
        private int     initialRcvWnd       = TCP_DEFAULT_RCV_BUF;
        private int     windowScale         = 7;
        private boolean timestampsEnabled   = true;
        private boolean windowScalingEnabled = true;
        private long    finWait2TimeoutMs   = FIN_WAIT_2_TIMEOUT_MS;
        private long    timeWaitMs          = TIME_WAIT_MS;
        private long    delayedAckMs        = DELAYED_ACK_MS;

        public Builder mss(int v)                   { this.mss = v; return this; }
        public Builder initialRcvWnd(int v)         { this.initialRcvWnd = v; return this; }
        public Builder windowScale(int v)           { this.windowScale = v; return this; }
        public Builder timestampsEnabled(boolean v) { this.timestampsEnabled = v; return this; }
        public Builder windowScalingEnabled(boolean v){ this.windowScalingEnabled = v; return this; }
        public Builder finWait2TimeoutMs(long v)    { this.finWait2TimeoutMs = v; return this; }
        public Builder timeWaitMs(long v)           { this.timeWaitMs = v; return this; }
        public Builder delayedAckMs(long v)         { this.delayedAckMs = v; return this; }

        public TcpConfig build() { return new TcpConfig(this); }
    }
}
