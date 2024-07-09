package com.github.pangolin.routing.proxy.group.lb;

import com.netflix.client.config.PropertyResolver;
import com.netflix.client.config.ReloadableClientConfig;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240709
 */
public class DefaultClientConfig extends ReloadableClientConfig {

    public DefaultClientConfig() {
        super(new PropertyResolver() {
            @Override
            public <T> Optional<T> get(final String s, final Class<T> aClass) {
                return Optional.empty();
            }

            @Override
            public void forEach(final String s, final BiConsumer<String, String> biConsumer) {

            }

            @Override
            public void onChange(final Runnable runnable) {

            }
        });
    }

    @Override
    public void loadDefaultValues() {

    }

    @Override
    public String resolveDeploymentContextbasedVipAddresses() {
        return null;
    }
}
