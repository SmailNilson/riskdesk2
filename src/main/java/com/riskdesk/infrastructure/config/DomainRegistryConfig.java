package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.ActiveContractPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainRegistryConfig {

    @Bean
    public ActiveContractRegistry activeContractRegistry(ActiveContractPersistencePort port) {
        return new ActiveContractRegistry(port);
    }
}
