package com.github.pangolin.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class NlbApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(NlbApplication.class, args);
        Application.main(args);
    }

}

