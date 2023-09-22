package com.github.pangolin.routing.internal.server.clash;

import lombok.Getter;
import lombok.Setter;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.PropertySubstitute;
import org.yaml.snakeyaml.representer.Representer;

import java.io.InputStream;
import java.util.List;

/**
 *
 */
@Getter
@Setter
public class Configuration {
    @Getter
    @Setter
    public static class Proxy {
        private String name;
        private String type;
        private String server;
        private String port;
        private String password;
    }

    @Getter
    @Setter
    public static class ProxyGroup {
        private String name;
        private String type;
        private List<String> proxies;
    }

    private List<Proxy> proxies;
    private List<ProxyGroup> proxyGroups;
    private List<String> rules;

    public static Configuration load(final InputStream in) {
        final TypeDescription typeDescription = new TypeDescription(Configuration.class);
        typeDescription.substituteProperty(new PropertySubstitute("proxy-groups", List.class, "getProxyGroups", "setProxyGroups", ProxyGroup.class));

        final Representer representer = new Representer();
        representer.getPropertyUtils().setSkipMissingProperties(true);

        final Yaml yaml = new Yaml(representer);
        yaml.addTypeDescription(typeDescription);
        return yaml.loadAs(in, Configuration.class);
    }

}
