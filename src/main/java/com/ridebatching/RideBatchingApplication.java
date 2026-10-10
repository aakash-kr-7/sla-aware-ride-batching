package com.ridebatching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RideBatchingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideBatchingApplication.class, args);
    }
}
