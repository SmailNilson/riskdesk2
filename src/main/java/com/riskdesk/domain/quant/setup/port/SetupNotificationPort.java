package com.riskdesk.domain.quant.setup.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupRecommendation;

/** Output port broadcasting setup recommendations to frontend subscribers. */
public interface SetupNotificationPort {

    /** Publishes a new or updated setup recommendation over WebSocket. */
    void publish(Instrument instrument, SetupRecommendation recommendation);
}
