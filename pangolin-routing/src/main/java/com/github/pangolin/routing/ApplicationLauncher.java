package com.github.pangolin.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class ApplicationLauncher {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(ApplicationLauncher.class, args);
        Application.main(args);
    }

}

