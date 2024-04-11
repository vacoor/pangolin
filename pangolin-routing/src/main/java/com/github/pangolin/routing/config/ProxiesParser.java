package com.github.pangolin.routing.config;

import com.github.pangolin.routing.config.clash.ClashProxyServerProviderFactory;
import com.github.pangolin.routing.config.spi.ServerResolver;
import com.github.pangolin.routing.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import freework.io.IOUtils;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240410
 */
public class ProxiesParser {

  public static ProxyServerProvider parse(final InputStream conf, final EventLoopGroup group) throws IOException {
    return resolve(new InputStreamReader(conf, StandardCharsets.UTF_8), group);
  }

  public static ComposedProxyServerProvider resolve(final Reader reader, final EventLoopGroup group) throws IOException {
    ObjectUtil.checkNotNull(reader, "reader");
    final Map<String, ProxyServer> fixedServers = new LinkedHashMap<>();
    final List<ProxyServerProvider> providers = new LinkedList<>();

    final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
    try {
      String line;
      while (null != (line = r.readLine())) {
        final String lineToUse = line.startsWith("#") ? "" : line.trim();
        if (lineToUse.isEmpty()) {
          continue;
        }

        final int i = lineToUse.indexOf('=');
        if (-1 < i) {
          final String name = lineToUse.substring(0, i).trim();
          final String url = lineToUse.substring(i + 1).trim();
          if (url.startsWith("subscribe:")) {
            final String subscribeUrl = url.substring("subscribe:".length());
            ProxyServerProvider proxyServerProvider = ClashProxyServerProviderFactory.create(subscribeUrl).getProxyServerProvider(group);
            providers.add(proxyServerProvider);
          } else {
            final ProxyServer proxyServer = resolve(url);
            final ProxyServer proxyServerToUse = new ProxyServer() {
              @Override
              public String getName() {
                return name;
              }

              @Override
              public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
                return proxyServer.newProxyHandler(sa);
              }
            };
            fixedServers.put(proxyServer.getName(), proxyServerToUse);
          }
        } else {
          // log
        }
      }
    } finally {
      IOUtils.close(reader);
    }
    final ProxyServerProvider[] providersToUse = providers.toArray(new ProxyServerProvider[providers.size() + 1]);
    providersToUse[providers.size()] = new ProxyServerProvider() {
      @Override
      public Collection<ProxyServer> getInstances() {
        return fixedServers.values();
      }

      @Override
      public ProxyServer getInstance(final String name) {
        return fixedServers.get(name);
      }
    };
    return new ComposedProxyServerProvider(providersToUse);
  }

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
    throw new IllegalStateException("NOT found provider: url :" + url);
  }

}
