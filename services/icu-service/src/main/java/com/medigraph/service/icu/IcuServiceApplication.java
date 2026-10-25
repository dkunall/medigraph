package com.medigraph.service.icu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IcuServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IcuServiceApplication.class, args);
    }
}
