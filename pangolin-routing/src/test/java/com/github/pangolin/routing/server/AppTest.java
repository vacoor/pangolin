package com.github.pangolin.routing.server;

import com.github.pangolin.routing.RouteApplication;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.SubnetRoutePredicate;
import com.google.common.collect.Lists;
import freework.io.IOUtils;
import org.junit.Test;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;

/**
 */
public class AppTest {
    @Test
    public void test2() throws Exception {
//        final Acceptor acceptor = new MixinAcceptorFactory().apply(1089);
//        acceptor.start(new InMemoryRouteContext(null)).sync().channel().closeFuture().sync();
//        RouteApplication.main(new String[0]);
        final ApplicationHome home = new ApplicationHome(AppTest.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final RouteApplication app = new RouteApplication();
        final RouteContext context = app.run(conf);

        final List<String> routes = Lists.newLinkedList();
        final List<String> addRouteCommands = Lists.newLinkedList();
        final List<String> deleteRouteCommands = Lists.newLinkedList();
        for (Route<InetSocketAddress> route : context.routes()) {
            for (RoutePredicate predicate : route.getPredicates()) {
                if (predicate instanceof SubnetRoutePredicate) {
                    SubnetRoutePredicate p = (SubnetRoutePredicate) predicate;
                    String s = p.getNetworkAddress().getHostAddress() + "/" + p.getCidrPrefix();
//                    System.out.println(String.format("sudo route add -net %s 198.18.0.1", s));
                    routes.add(s);
                    addRouteCommands.add(String.format("sudo route add -net %s 198.18.0.1", s));
                    deleteRouteCommands.add(String.format("sudo route delete -net %s", s));
                }
            }
        }

        System.out.println("-----------------------");
        System.out.println("----------- Add route commands ----------");
        final FileWriter addRouteWriter = new FileWriter(new File(home.getDir(), "tun_add_route.sh"));
        for (String addRouteCommand : addRouteCommands) {
//            System.out.println(addRouteCommand);
            addRouteWriter.write(addRouteCommand);
            addRouteWriter.write("\r\n");
        }
        addRouteWriter.flush();
        IOUtils.close(addRouteWriter);

        final FileWriter deleteRouteWriter = new FileWriter(new File(home.getDir(), "tun_delete_route.sh"));
        System.out.println("----------- Delete route commands ----------");
        for (String deleteRouteCommand : deleteRouteCommands) {
//            System.out.println(deleteRouteCommand);
            deleteRouteWriter.write(deleteRouteCommand);
            deleteRouteWriter.write("\r\n");
        }
        deleteRouteWriter.flush();
        IOUtils.close(deleteRouteWriter);
        System.out.println("-----------------------");

        app.await();
    }

}
