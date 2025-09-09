package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState;

import java.util.concurrent.atomic.AtomicReference;

public abstract class Sock {
    public final AtomicReference<TcpState> state = new AtomicReference<>(TcpState.TCP_CLOSE);

    public int sk_shutdown;
    public int sk_err_soft;

    public TcpState state() {
        return state.get();
    }

    public void state(final TcpState state) {
        this.state.set(state);
    }

}
