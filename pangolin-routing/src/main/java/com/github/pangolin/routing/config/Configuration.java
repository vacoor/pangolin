package com.github.pangolin.routing.config;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

@Slf4j
public class Configuration {
    private final File homeFile;

    public Configuration(final File homeFile) {
        this.homeFile = homeFile;
    }

    public void refresh() {
        final File proxiesConf = new File(homeFile, "conf/proxies.conf");
        final File rulesConf = new File(homeFile, "conf/rule.conf");

        log.info("Rules config: " + rulesConf.getAbsolutePath());
        log.info("Proxies config: " + proxiesConf.getAbsolutePath());

//        ProxyServerProvider proxyServerProvider = proxiesConf.exists() ? ProxiesParser.parse(new FileInputStream(proxiesConf), group) : new ComposedProxyServerProvider();

//        final Map<DestinationPattern, String> rules = rulesConf.exists() ? RulesParser.parseRules(rulesConf.toURI().toURL()) : Collections.emptyMap();
    }
}
