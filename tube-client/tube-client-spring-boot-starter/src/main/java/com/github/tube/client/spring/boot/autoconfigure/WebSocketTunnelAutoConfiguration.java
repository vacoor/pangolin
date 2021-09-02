package com.github.tube.client.spring.boot.autoconfigure;

import com.github.tube.client.servlet.WebSocketTunnelServlet;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 *
 */
@Configuration
@ServletComponentScan(basePackageClasses = {WebSocketTunnelServlet.class})
public class WebSocketTunnelAutoConfiguration {
}
