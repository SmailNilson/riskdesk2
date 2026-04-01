package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainRegistryConfig {

    @Bean
    public ActiveContractRegistry activeContractRegistry() {
        return new ActiveContractRegistry();
    }
}
