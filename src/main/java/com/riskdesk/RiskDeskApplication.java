package com.riskdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RiskDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskDeskApplication.class, args);
    }
}
