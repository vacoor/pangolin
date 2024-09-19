package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.RouteApplication;
import com.github.pangolin.routing.context.RouteContext;
import org.junit.Test;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.net.URL;

/**
 *
 */
public class TunBasedApplication {

    @Test
    public void test2() throws Exception {
        final ApplicationHome home = new ApplicationHome(TunBasedApplication.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();

        final RouteApplication app = new RouteApplication();
        final RouteContext context = app.run(conf);

        app.await();
    }

}
