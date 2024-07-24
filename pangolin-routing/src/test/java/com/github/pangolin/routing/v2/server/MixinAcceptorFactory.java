package com.github.pangolin.routing.v2.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.v2.context.RouteContext;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.ServiceLoader;

@Slf4j
public class MixinAcceptorFactory implements AcceptorFactory {
    private final Map<String, MixinAcceptorHandshakerFactory> handshakers = Maps.newLinkedHashMap();

    public MixinAcceptorFactory() {
        this(ServiceLoader.load(MixinAcceptorHandshakerFactory.class));
    }

    public MixinAcceptorFactory(final Iterable<MixinAcceptorHandshakerFactory> handshakers) {
        this.initHandshakerFactories(handshakers);
    }

    private void initHandshakerFactories(final Iterable<MixinAcceptorHandshakerFactory> factories) {
        for (final MixinAcceptorHandshakerFactory factory : factories) {
            final String key = factory.name();
            if (handshakers.containsKey(key)) {
                log.warn("A MixinAcceptorHandshakerFactory named " + key
                        + " already exists, class: " + handshakers.get(key)
                        + ". It will be overwritten.");
            }
            handshakers.put(key, factory);
            log.info("Loaded MixinAcceptorHandshakerFactory [" + key + "]");
        }
    }

    @Override
    public Acceptor apply(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory, final String... args) {
        for (final String name : args) {
            final MixinAcceptorHandshakerFactory factory = handshakers.get(name);
            if (null == factory) {
                throw new IllegalArgumentException("Unable to create MixinAcceptorHandshakerFactory with name " + name);
            }
        }
        // FIXME
        return null;
    }

    public static void main(String[] args) {
        new MixinAcceptorFactory();
    }

}
