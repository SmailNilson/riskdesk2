package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;

import java.util.Optional;

/**
 * Domain port for persisting flash crash detection thresholds per instrument.
 */
public interface FlashCrashConfigPort {

    Optional<FlashCrashThresholds> loadThresholds(Instrument instrument);

    void saveThresholds(Instrument instrument, FlashCrashThresholds thresholds);

    void deleteThresholds(Instrument instrument);
}
