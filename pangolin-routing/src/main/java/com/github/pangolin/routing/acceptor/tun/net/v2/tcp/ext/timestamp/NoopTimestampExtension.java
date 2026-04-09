package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.timestamp;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;

/**
 * No-op timestamp extension: timestamps disabled, PAWS never rejects.
 */
public final class NoopTimestampExtension implements TcpTimestampExtension {

    public static final NoopTimestampExtension INSTANCE = new NoopTimestampExtension();

    private NoopTimestampExtension() {}

    @Override public void    init(TcpConnection conn)                          {}
    @Override public boolean isEnabled(TcpConnection conn)                     { return false; }
    @Override public int     buildTsval(TcpConnection conn)                    { return 0; }
    @Override public boolean isPawsRejected(TcpConnection conn, int tsval)     { return false; }
    @Override public void    updateRecent(TcpConnection conn, int tsval)       {}
    @Override public void    onConnectionClosed(TcpConnection conn)            {}
}
