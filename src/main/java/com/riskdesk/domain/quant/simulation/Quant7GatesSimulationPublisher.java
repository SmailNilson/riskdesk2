package com.riskdesk.domain.quant.simulation;

/**
 * Output port for streaming {@link Quant7GatesSimulation} lifecycle changes to
 * the frontend. Implemented by a STOMP adapter in {@code infrastructure/quant}.
 */
public interface Quant7GatesSimulationPublisher {

    /** Broadcast a new open/closed/updated simulation row to all subscribers. */
    void publish(Quant7GatesSimulation simulation);
}
