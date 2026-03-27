package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.application.service.ActiveContractService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.dto.LivePriceView;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live-price")
@CrossOrigin(origins = "*")
@Profile("!test")
public class LivePriceController {

    private final MarketDataService marketDataService;
    private final ActiveContractService activeContractService;

    public LivePriceController(MarketDataService marketDataService,
                               ActiveContractService activeContractService) {
        this.marketDataService = marketDataService;
        this.activeContractService = activeContractService;
    }

    @GetMapping("/{instrument}")
    public ResponseEntity<LivePriceView> latest(@PathVariable String instrument) {
        Instrument parsed;
        try {
            parsed = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        MarketDataService.StoredPrice stored = marketDataService.currentPrice(parsed);
        if (stored == null) {
            return ResponseEntity.notFound().build();
        }

        ActiveContractService.ActiveContractDescriptor activeContract = activeContractService.describe(parsed);
        return ResponseEntity.ok(new LivePriceView(
            parsed.name(),
            stored.price(),
            stored.timestamp(),
            stored.source(),
            activeContract.asset(),
            activeContract.contractMonth(),
            activeContract.contractSymbol(),
            activeContract.selectionReason()
        ));
    }
}
