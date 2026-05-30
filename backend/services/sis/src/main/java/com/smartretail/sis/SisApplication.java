package com.smartretail.sis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SisApplication {
    public static void main(String[] args) {
        SpringApplication.run(SisApplication.class, args);
    }
}
