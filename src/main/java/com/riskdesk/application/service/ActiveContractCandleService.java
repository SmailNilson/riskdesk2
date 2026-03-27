package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ActiveContractCandleService {

    private final CandleRepositoryPort candleRepositoryPort;
    private final ActiveContractService activeContractService;

    public ActiveContractCandleService(CandleRepositoryPort candleRepositoryPort,
                                       ActiveContractService activeContractService) {
        this.candleRepositoryPort = candleRepositoryPort;
        this.activeContractService = activeContractService;
    }

    public List<Candle> findCandles(Instrument instrument, String timeframe, Instant from) {
        String contractMonth = activeContractMonth(instrument);
        if (contractMonth != null) {
            List<Candle> aligned = candleRepositoryPort.findCandles(instrument, timeframe, contractMonth, from);
            if (!aligned.isEmpty()) {
                return aligned;
            }
        }
        return candleRepositoryPort.findCandles(instrument, timeframe, from);
    }

    public List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit) {
        String contractMonth = activeContractMonth(instrument);
        if (contractMonth != null) {
            List<Candle> aligned = candleRepositoryPort.findRecentCandles(instrument, timeframe, contractMonth, limit);
            if (!aligned.isEmpty()) {
                return aligned;
            }
        }
        return candleRepositoryPort.findRecentCandles(instrument, timeframe, limit);
    }

    public String activeContractMonth(Instrument instrument) {
        String contractMonth = activeContractService.describe(instrument).contractMonth();
        return contractMonth == null || contractMonth.isBlank() ? null : contractMonth;
    }
}
