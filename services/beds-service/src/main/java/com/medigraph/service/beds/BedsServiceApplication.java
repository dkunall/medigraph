package com.medigraph.service.beds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BedsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BedsServiceApplication.class, args);
    }
}
