package com.github.pangolin.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.system.ApplicationHome;

/**
 *
 */
@Slf4j
@SpringBootApplication
public class RoutingServerApplication {
  public static void main(String[] args) throws Exception {
    final ApplicationHome home = new ApplicationHome(RoutingServerApplication.class);
    System.out.println(home.getDir());
    SpringApplication.run(RoutingServerApplication.class, args);
    ServerMain.main(args);
  }
}
