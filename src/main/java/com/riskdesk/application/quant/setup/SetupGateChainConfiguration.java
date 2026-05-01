package com.riskdesk.application.quant.setup;

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
 * Wires the setup gate chain. Lives in the application layer because the
 * gates themselves are application classes — instantiating them from
 * infrastructure would violate the hexagonal layering rule.
 */
@Configuration
public class SetupGateChainConfiguration {

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
