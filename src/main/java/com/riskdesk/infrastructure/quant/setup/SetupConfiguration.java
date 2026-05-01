package com.riskdesk.infrastructure.quant.setup;

import com.riskdesk.application.quant.setup.gate.LiveDataGate;
import com.riskdesk.application.quant.setup.gate.RegimeGate;
import com.riskdesk.application.quant.setup.gate.ScoreGate;
import com.riskdesk.application.quant.setup.gate.SessionGate;
import com.riskdesk.application.quant.setup.gate.StructuralGate;
import com.riskdesk.domain.quant.setup.SetupGate;
import com.riskdesk.domain.quant.setup.SetupGateChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the setup gate chain and related beans.
 * Kept in the infrastructure layer so domain / application classes stay
 * free of Spring annotations.
 */
@Configuration
public class SetupConfiguration {

    @Bean
    public SetupGateChain setupGateChain() {
        List<SetupGate> gates = List.of(
            new LiveDataGate(),
            new ScoreGate(),
            new RegimeGate(),
            new StructuralGate(),
            new SessionGate()
        );
        return new SetupGateChain(gates);
    }
}
