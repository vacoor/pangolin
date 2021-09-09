package com.github.pangolin.client.spring.boot.autoconfigure;

import com.github.pangolin.client.servlet.WebSocketTunnelServlet;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 *
 */
@Configuration
@ServletComponentScan(basePackageClasses = {WebSocketTunnelServlet.class})
public class WebSocketTunnelAutoConfiguration {

    /**
     * 没有方法上传不了
     */
    public void test() {
        // 没有方法上传不了
    }

}
