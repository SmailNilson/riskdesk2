package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.application.service.WatchlistDashboardService;
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
    private final WatchlistDashboardService watchlistDashboardService;

    public LivePriceController(MarketDataService marketDataService,
                               WatchlistDashboardService watchlistDashboardService) {
        this.marketDataService = marketDataService;
        this.watchlistDashboardService = watchlistDashboardService;
    }

    @GetMapping("/{instrument}")
    public ResponseEntity<LivePriceView> latest(@PathVariable String instrument) {
        try {
            Instrument parsed = Instrument.valueOf(instrument.toUpperCase());
            MarketDataService.StoredPrice stored = marketDataService.currentPrice(parsed);
            if (stored == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(new LivePriceView(parsed.name(), stored.price(), stored.timestamp(), stored.source()));
        } catch (IllegalArgumentException ignored) {
            return watchlistDashboardService.currentPrice(instrument)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        }
    }
}
