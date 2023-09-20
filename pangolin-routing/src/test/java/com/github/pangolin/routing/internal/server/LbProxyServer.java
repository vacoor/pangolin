package com.github.pangolin.routing.internal.server;

import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LbProxyServer implements ProxyServer {
    private final String name;
    private final Map<String, ProxyServer> servers = new ConcurrentHashMap<>();
    private List<ProxyServer> aliveServers;
    private List<ProxyServer> deadServers = new CopyOnWriteArrayList<>();

    public LbProxyServer(final String name, final List<ProxyServer> aliveServers) {
        this.name = name;
        for (ProxyServer aliveServer : aliveServers) {
            servers.put(aliveServer.name(), aliveServer);
        }
        updateAlive();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        return aliveServers.get(new Random().nextInt(aliveServers.size())).newProxyHandler();
    }

    private void updateAlive() {
        synchronized (servers) {
            aliveServers = new LinkedList<>(servers.values());
        }
    }

    final EventLoopGroup g = new NioEventLoopGroup();

    public void check() {
        for (Map.Entry<String, ProxyServer> entry : servers.entrySet()) {
            final String name = entry.getKey();
            g.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkAlive(entry.getValue());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, 0, TimeUnit.SECONDS);
        }
    }

    private void checkAlive(final ProxyServer s) throws Exception {
        ChannelHandler channelHandler = s.newProxyHandler();
        Channels.open(
                new InetSocketAddress("www.gstatic.com", 80),
                NoopAddressResolverGroup.INSTANCE, true, g, channelHandler
        ).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    ProxyServer dead = servers.remove(s.name());
                    deadServers.add(dead);
                    log.info("alive: {}, dead: {}", aliveServers, deadServers);
                    updateAlive();
                }
            }
        });
    }

}