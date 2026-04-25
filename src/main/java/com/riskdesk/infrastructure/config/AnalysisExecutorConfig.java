package com.riskdesk.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated thread pool for the analysis snapshot fan-out so it does not
 * compete with the IBKR EWrapper threads or the Spring scheduler pool.
 */
@Configuration
public class AnalysisExecutorConfig {

    @Bean(name = "analysisExecutor")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("analysis-snapshot-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
    }
}
