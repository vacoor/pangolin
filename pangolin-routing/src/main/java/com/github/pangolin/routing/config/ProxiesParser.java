package com.github.pangolin.routing.config;

import com.github.pangolin.routing.config.clash.SubConfiguration;
import com.github.pangolin.routing.proxy.ServerFactory;
import com.github.pangolin.routing.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import freework.io.IOUtils;
import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 */
public class ProxiesParser {

  public static ProxyServerProvider parse(final InputStream conf, final ServerFactory factory) throws IOException {
    return resolve(new InputStreamReader(conf, StandardCharsets.UTF_8), factory);
  }

  public static ComposedProxyServerProvider resolve(final Reader reader, final ServerFactory factory) throws IOException {
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
            ProxyServerProvider proxyServerProvider = new SubConfiguration(new URL(subscribeUrl), factory).refresh().getServerProvider();
            providers.add(proxyServerProvider);
          } else {
            fixedServers.put(name, factory.resolve(name, url));
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

}
