package com.github.pangolin.routing.internal.server;

import com.github.pangolin.routing.RoutingRule;
import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.Socks5RoutingServerHandler;
import com.github.pangolin.routing.internal.server.ss.SsProxyHandler;
import com.github.pangolin.routing.internal.server.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.internal.server.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.server.NettyServer;
import freework.codec.Base64;
import freework.net.Http;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.EventExecutor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;

/**
 *
 */
public class Server {

    private static ProxyServer resolve(final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, null);
            if (null != resolved) {
                return resolved;
            }
        }
        return null;
    }

    private static DestinationPattern resolvePattern(final String pattern) {
        return new DomainPattern("**.youtube.com");
    }

    public static List<ProxyServer> load(String url) throws Exception {
        final List<ProxyServer> servers = new ArrayList<>();
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = Http.get(url);
            httpUrlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36");
            final int responseCode = httpUrlConnection.getResponseCode();
            final InputStream in = Base64.wrap(httpUrlConnection.getInputStream(), false);
            final List<String> read = IOUtils.read(in);
            for (String line : read) {
                servers.add(resolve(line));
            }
        } finally {
            Http.close(httpUrlConnection);
        }
        return servers;
    }

    public static void main(String[] args) throws Exception {
        final List<RoutingRule> routingRules = new LinkedList<>();
        /*
        final String url = "ss://aes-128-gcm:653addca-f87d-49e1-8fc1-235fa53a8ccd";
        final ProxyServer resolveServer = resolve(url);
        final RoutingRule rule = new RoutingRule(resolvePattern(null), resolveServer::newProxyHandler);
        */
        /*-
         * destination, server
         *
         * servers = select(destination);
         * servers.next();
         */

        final String subscribeUrl = "https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b0326";
        final List<ProxyServer> servers = load(subscribeUrl);

        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();
        for (ProxyServer server : servers) {
            LbProxyServer.checkAlive(nioEventLoopGroup, server);
        }

        /*
        final Socks5RoutingServer server = new Socks5RoutingServer(1080);
        server.start(routingRules).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("Server started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
        */
        /*-
        Connect through local to http://bing.com/ failed.
        Error: Connection refused
         */
    }

}
