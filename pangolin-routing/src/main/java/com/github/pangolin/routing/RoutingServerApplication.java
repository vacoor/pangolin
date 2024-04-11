package com.github.pangolin.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 *
 */
@Slf4j
@SpringBootApplication
public class RoutingServerApplication {
  public static void main(String[] args) throws Exception {
    SpringApplication.run(RoutingServerApplication.class, args);
    ServerMain.main(args);
  }
}
