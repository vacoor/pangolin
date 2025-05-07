package com.github.pangolin.routing.acceptor.mixin;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.AcceptorFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
public class MixinAcceptorFactory implements AcceptorFactory {

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String upstream = args.length > 0 ? args[0] : null;
        return new MixinAcceptor(listenPort, upstream, Arrays.copyOfRange(args, 1, args.length));
    }

}
