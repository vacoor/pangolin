package com.github.pangolin.routing.acceptor.tun;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.AcceptorFactory;
import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class TunAcceptorFactory implements AcceptorFactory {

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String defName = Platform.isWindows() ? "以太网 P" : null;
        final String ifname = args.length > 0 ? args[0] : defName;
        return new TunAcceptor(ifname);
    }


}
