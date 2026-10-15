package com.sixpack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SixPackApplication {

    public static void main(String[] args) {
        SpringApplication.run(SixPackApplication.class, args);
    }
}
