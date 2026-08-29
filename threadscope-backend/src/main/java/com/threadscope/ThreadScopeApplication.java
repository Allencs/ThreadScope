package com.threadscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ThreadScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThreadScopeApplication.class, args);
    }
}
