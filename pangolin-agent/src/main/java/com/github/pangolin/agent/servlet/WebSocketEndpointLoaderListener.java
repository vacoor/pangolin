package com.github.pangolin.agent.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.util.Arrays;

// @WebListener
public class WebSocketEndpointLoaderListener implements ServletContextListener {
    private static final String WEB_SOCKET_ENDPOINT_NAME = "javax.websocket.Endpoint";
    private static final boolean WEB_SOCKET_SUPPORTED = isPresent(WEB_SOCKET_ENDPOINT_NAME);
    private static final String WEB_SOCKET_CONTAINER_KEY = "javax.websocket.server.ServerContainer";
    private static final String SERVLET_CONTEXT_KEY = ServletContext.class.getName();

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextInitialized(final ServletContextEvent event) {
        final ServletContext ctx = event.getServletContext();

        if (!WEB_SOCKET_SUPPORTED) {
            log.warn("JSR-356 WebSocket is not support...");
            return;
        }

        if (log.isInfoEnabled()) {
            log.info("Loading JSR-356 WebSocket support...");
        }

        final ServerContainer container = (ServerContainer) ctx.getAttribute(WEB_SOCKET_CONTAINER_KEY);
        if (null == container) {
            log.warn("ServerContainer attribute required by JSR-356 is missing. Cannot load JSR-356 WebSocket support.");
            return;
        }

        for (final Class<?> endpointClass : getAnnotatedEndpointClasses()) {
            try {
                if (log.isInfoEnabled()) {
                    log.info("Deploy WebSocket endpoint: {}", endpointClass);
                }

                final ServerEndpointConfig serverEndpointConfig = this.customizeServerEndpointConfig(endpointClass, ctx);
                if (null != serverEndpointConfig) {
                    container.addEndpoint(serverEndpointConfig);
                } else {
                    container.addEndpoint(endpointClass);
                }
            } catch (final DeploymentException e) {
                log.error("Deploy WebSocket endpoint error", e);
            }
        }
    }

    private ServerEndpointConfig customizeServerEndpointConfig(final Class<?> endpointClass,
                                                               final ServletContext webSocketServletContext) throws DeploymentException {
        final ServerEndpoint annotation = endpointClass.getAnnotation(ServerEndpoint.class);
        if (null != annotation) {
            ServerEndpointConfig.Configurator configurator = null;
            final Class<? extends ServerEndpointConfig.Configurator> configuratorClass = annotation.configurator();
            if (!configuratorClass.equals(ServerEndpointConfig.Configurator.class)) {
                try {
                    configurator = annotation.configurator().newInstance();
                } catch (final InstantiationException | IllegalAccessException e) {
                    throw new DeploymentException(String.format("Failed to create configurator of type [%s] for POJO of type [%s]", annotation.configurator().getName(), endpointClass.getName()), e);
                }
            }
            final ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(endpointClass, annotation.value()).
                    decoders(Arrays.asList(annotation.decoders())).
                    encoders(Arrays.asList(annotation.encoders())).
                    subprotocols(Arrays.asList(annotation.subprotocols())).
                    configurator(configurator).
                    build();
            endpointConfig.getUserProperties().put(SERVLET_CONTEXT_KEY, webSocketServletContext);
            return endpointConfig;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextDestroyed(final ServletContextEvent event) {
    }

    private Class<?>[] getAnnotatedEndpointClasses() {
        return new Class<?>[]{WebSocketBridgeEndpoint.class};
    }

    /**
     * 类是否存在.
     *
     * @param className 类限定名
     * @return 类存在返回true, 否则false
     */
    private static boolean isPresent(final String className) {
        try {
            Class.forName(className);
            return true;
        } catch (final Exception ignore) {
            // ignore
        }
        return false;
    }
}
