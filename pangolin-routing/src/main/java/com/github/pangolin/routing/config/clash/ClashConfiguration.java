package com.github.pangolin.routing.config.clash;

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
public class ClashConfiguration {

    @Getter
    @Setter
    public static class ProxyDefinition {
        private String name;
        private String type;
        private String server;
        private int port;
        private String cipher;
        private String password;
        private boolean udp;
    }

    @Getter
    @Setter
    public static class ProxyGroupDefinition {
        private String name;
        private String type;
        private String url;
        private List<String> proxies;
    }

    private List<ProxyDefinition> proxies;
    private List<ProxyGroupDefinition> proxyGroups;

    private List<String> rules;

    public static ClashConfiguration load(final InputStream in) {
        final TypeDescription typeDescription = new TypeDescription(ClashConfiguration.class);
        typeDescription.substituteProperty(new PropertySubstitute(
                "proxy-groups", List.class,
                "getProxyGroups", "setProxyGroups", ProxyGroupDefinition.class
        ));

        final Representer representer = new Representer();
        representer.getPropertyUtils().setSkipMissingProperties(true);

        final Yaml yaml = new Yaml(representer);
        yaml.addTypeDescription(typeDescription);
        return yaml.loadAs(in, ClashConfiguration.class);
    }

}
