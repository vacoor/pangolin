package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;

import java.util.function.Consumer;

/**
 * No-op congestion control: always reports max window, never enters recovery.
 * Used when RFC 5681 is disabled (e.g. unit tests or minimal configurations).
 */
public final class NoopCongestionControl implements CongestionControl {

    public static final NoopCongestionControl INSTANCE = new NoopCongestionControl();

    private NoopCongestionControl() {}

    @Override public String name()                                                            { return "noop"; }
    @Override public void   init(TcpConnection conn, Consumer<TcpConnection> cb)             {}
    @Override public void   onAck(TcpConnection conn, int ackedSegs, boolean advanced)       {}
    @Override public void   onTimeout(TcpConnection conn)                                    {}
    @Override public int    cwnd(TcpConnection conn)                                         { return TcpConstants.TCP_INIT_CWND; }
    @Override public boolean isInRecovery(TcpConnection conn)                                { return false; }
    @Override public void   onConnectionClosed(TcpConnection conn)                           {}
}
