package com.github.pangolin.routing.server;

import com.github.pangolin.routing.RouteApplication;
import org.junit.Test;

/**
 */
public class AppTest {
    @Test
    public void test2() throws Exception {
//        final Acceptor acceptor = new MixinAcceptorFactory().apply(1089);
//        acceptor.start(new InMemoryRouteContext(null)).sync().channel().closeFuture().sync();
        RouteApplication.main(new String[0]);
    }

}
