package com.devopsaaas.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service the agent diagnoses in the demo. It is deliberately breakable: its chaos endpoints have no
 * authentication and are published on 127.0.0.1 only. It is a demo target, not a template for real services.
 */
@SpringBootApplication
public class DemoApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApiApplication.class, args);
    }
}
