package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.*;

/**
 * Immutable per-listener TCP configuration.
 * Shared across connections; constructed once at listener startup.
 *
 * <p>可选特性开关({@code sackEnabled} / {@code pmtudEnabled} / {@code ecnEnabled} /
 * {@code tfoEnabled})按 {@code tcp.rfc.md} 档位 B 推荐默认值:SACK / TS / WScale /
 * PMTUD / 5961 防护默认开;ECN / TFO 默认关(外层 tunnel 不保证 IP TOS 透传)。
 *
 * <p>Phase E(v2 TCP 包重构 · 提交 3)仅新增字段,<b>本提交不消费</b> — 现有协议
 * 代码继续按常量 / 已有字段分支走;字段接通由后续专项 phase 完成。
 */
public final class TcpConfig {

    private final int     mss;
    private final int     initialRcvWnd;
    private final int     windowScale;
    private final boolean timestampsEnabled;
    private final boolean windowScalingEnabled;
    private final boolean sackEnabled;
    private final boolean pmtudEnabled;
    private final boolean ecnEnabled;
    private final boolean tfoEnabled;
    private final long    finWait2TimeoutMs;
    private final long    timeWaitMs;
    private final long    delayedAckMs;

    private TcpConfig(Builder b) {
        this.mss                  = b.mss;
        this.initialRcvWnd        = b.initialRcvWnd;
        this.windowScale          = b.windowScale;
        this.timestampsEnabled    = b.timestampsEnabled;
        this.windowScalingEnabled = b.windowScalingEnabled;
        this.sackEnabled          = b.sackEnabled;
        this.pmtudEnabled         = b.pmtudEnabled;
        this.ecnEnabled           = b.ecnEnabled;
        this.tfoEnabled           = b.tfoEnabled;
        this.finWait2TimeoutMs    = b.finWait2TimeoutMs;
        this.timeWaitMs           = b.timeWaitMs;
        this.delayedAckMs         = b.delayedAckMs;
    }

    public int     mss()                   { return mss; }
    public int     initialRcvWnd()         { return initialRcvWnd; }
    public int     windowScale()           { return windowScale; }
    public boolean timestampsEnabled()     { return timestampsEnabled; }
    public boolean windowScalingEnabled()  { return windowScalingEnabled; }
    /** RFC 2018 SACK 协商开关,档位 B 默认 {@code true}。 */
    public boolean sackEnabled()           { return sackEnabled; }
    /** RFC 4821 Packetization Layer PMTUD 开关,档位 B 默认 {@code true}。 */
    public boolean pmtudEnabled()          { return pmtudEnabled; }
    /** RFC 3168 ECN 协商与 CE 响应开关,默认 {@code false}(tunnel 场景 ECN 透传不可靠)。 */
    public boolean ecnEnabled()            { return ecnEnabled; }
    /** RFC 7413 TCP Fast Open 开关,默认 {@code false}。 */
    public boolean tfoEnabled()            { return tfoEnabled; }
    public long    finWait2TimeoutMs()     { return finWait2TimeoutMs; }
    public long    timeWaitMs()            { return timeWaitMs; }
    public long    delayedAckMs()          { return delayedAckMs; }

    public static Builder builder()        { return new Builder(); }

    /** Default config for 1500-byte Ethernet MTU. */
    public static final TcpConfig DEFAULT = builder().build();

    public static final class Builder {
        private int     mss                  = TCP_MSS_1460;
        private int     initialRcvWnd        = TCP_DEFAULT_RCV_BUF;
        private int     windowScale          = 7;
        private boolean timestampsEnabled    = true;
        private boolean windowScalingEnabled = true;
        private boolean sackEnabled          = true;
        private boolean pmtudEnabled         = true;
        private boolean ecnEnabled           = false;
        private boolean tfoEnabled           = false;
        private long    finWait2TimeoutMs    = FIN_WAIT_2_TIMEOUT_MS;
        private long    timeWaitMs           = TIME_WAIT_MS;
        private long    delayedAckMs         = DELAYED_ACK_MS;

        public Builder mss(int v)                     { this.mss = v; return this; }
        public Builder initialRcvWnd(int v)           { this.initialRcvWnd = v; return this; }
        public Builder windowScale(int v)             { this.windowScale = v; return this; }
        public Builder timestampsEnabled(boolean v)   { this.timestampsEnabled = v; return this; }
        public Builder windowScalingEnabled(boolean v){ this.windowScalingEnabled = v; return this; }
        public Builder sackEnabled(boolean v)         { this.sackEnabled = v; return this; }
        public Builder pmtudEnabled(boolean v)        { this.pmtudEnabled = v; return this; }
        public Builder ecnEnabled(boolean v)          { this.ecnEnabled = v; return this; }
        public Builder tfoEnabled(boolean v)          { this.tfoEnabled = v; return this; }
        public Builder finWait2TimeoutMs(long v)      { this.finWait2TimeoutMs = v; return this; }
        public Builder timeWaitMs(long v)             { this.timeWaitMs = v; return this; }
        public Builder delayedAckMs(long v)           { this.delayedAckMs = v; return this; }

        public TcpConfig build() { return new TcpConfig(this); }
    }
}
