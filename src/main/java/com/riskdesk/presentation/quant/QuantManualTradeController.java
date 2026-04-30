package com.riskdesk.presentation.quant;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.application.quant.automation.QuantManualTradeService;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualTradeRequest;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual trade-ticket endpoint backing the QuantGatePanel BUY/SELL buttons.
 */
@RestController
@RequestMapping("/api/quant/manual-trade")
public class QuantManualTradeController {

    private static final Logger log = LoggerFactory.getLogger(QuantManualTradeController.class);

    private final QuantManualTradeService manualTradeService;

    public QuantManualTradeController(QuantManualTradeService manualTradeService) {
        this.manualTradeService = manualTradeService;
    }

    @PostMapping("/{instrument}")
    public ResponseEntity<TradeExecutionView> place(@PathVariable String instrument,
                                                    @RequestBody ManualTradeRequest body,
                                                    @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        Instrument instr;
        try {
            instr = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported instrument: " + instrument, e);
        }
        if (!instr.isExchangeTradedFuture()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "instrument " + instr + " is synthetic — manual orders not supported");
        }

        try {
            TradeExecutionRecord placed = manualTradeService.place(instr, body, requestedBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(TradeExecutionView.from(placed));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.warn("manual-trade rejected instrument={} reason={}", instr, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }
}
