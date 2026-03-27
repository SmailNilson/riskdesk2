package com.riskdesk;

import com.riskdesk.infrastructure.config.LocalEnvironmentBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class RiskDeskApplication {
    public static void main(String[] args) {
        LocalEnvironmentBootstrap.loadIntoSystemProperties(Path.of("."));
        SpringApplication.run(RiskDeskApplication.class, args);
    }
}
