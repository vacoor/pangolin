package com.github.pangolin.routing.server;

import com.github.pangolin.routing.ServerMain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.system.ApplicationHome;

/**
 *
 */
@Slf4j
@SpringBootApplication
public class RoutingServerApplicationTest {
  public static void main(String[] args) throws Exception {
    final ApplicationHome home = new ApplicationHome(RoutingServerApplicationTest.class);
    System.out.println(home.getDir());
    SpringApplication.run(RoutingServerApplicationTest.class, args);
    ServerMain.main(args);
  }
}
