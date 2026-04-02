package com.riskdesk.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.marketdata.port.VolumeProvider;
import com.riskdesk.infrastructure.marketdata.ibkr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * Wires market data providers using IBKR only.
 */
@Configuration
@EnableConfigurationProperties(IbkrProperties.class)
public class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

    // -------------------------------------------------------------------------
    // Legacy IBKR Client Portal Gateway (conditional)
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'CLIENT_PORTAL'")
    public IbkrRestClient ibkrRestClient(IbkrProperties props) {
        return new IbkrRestClient(props);
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'CLIENT_PORTAL'")
    public IbkrContractCache ibkrContractCache(IbkrRestClient client, ObjectMapper objectMapper) {
        var cache = new IbkrContractCache(client.restTemplate(), objectMapper, client.baseUrl());
        cache.refreshConids();
        return cache;
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'CLIENT_PORTAL'")
    public IbkrMarketDataProvider ibkrMarketDataProvider(IbkrRestClient client,
                                                          ObjectMapper objectMapper,
                                                          IbkrContractCache contractCache) {
        return new IbkrMarketDataProvider(client, objectMapper, contractCache);
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'CLIENT_PORTAL'")
    public IbkrHistoricalProvider ibkrHistoricalProvider(IbkrRestClient client,
                                                          ObjectMapper objectMapper,
                                                          IbkrContractCache contractCache) {
        return new IbkrHistoricalProvider(client, objectMapper, contractCache);
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'IB_GATEWAY'")
    public IbGatewayMarketDataProvider ibGatewayMarketDataProvider(IbGatewayNativeClient nativeClient,
                                                                   IbGatewayContractResolver contractResolver) {
        return new IbGatewayMarketDataProvider(nativeClient, contractResolver);
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'IB_GATEWAY'")
    public IbGatewayHistoricalProvider ibGatewayHistoricalProvider(IbGatewayNativeClient nativeClient,
                                                                   IbGatewayContractResolver contractResolver) {
        return new IbGatewayHistoricalProvider(nativeClient, contractResolver);
    }

    @Bean
    @ConditionalOnProperty(name = "riskdesk.ibkr.enabled", havingValue = "true")
    public VolumeProvider ibkrVolumeProvider(IbGatewayNativeClient nativeClient,
                                             IbGatewayContractResolver contractResolver) {
        return new IbkrVolumeProvider(nativeClient, contractResolver);
    }

    @Bean
    @ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' != 'true'")
    public VolumeProvider noopVolumeProvider() {
        return (instrument, contractMonth) -> java.util.OptionalLong.empty();
    }

    // -------------------------------------------------------------------------
    // Primary composite providers
    // -------------------------------------------------------------------------

    @Bean
    @Primary
    public MarketDataProvider primaryMarketDataProvider(Optional<IbkrMarketDataProvider> ibkrProvider,
                                                        Optional<IbGatewayMarketDataProvider> ibGatewayProvider,
                                                        IbkrProperties ibkrProps) {
        if (ibkrProps.isEnabled() && ibkrProps.getMode() == IbkrBackendMode.CLIENT_PORTAL && ibkrProvider.isPresent()) {
            log.info("Live market data: IBKR Client Portal");
            return ibkrProvider.get();
        }
        if (ibkrProps.isEnabled() && ibkrProps.getMode() == IbkrBackendMode.IB_GATEWAY && ibGatewayProvider.isPresent()) {
            log.info("Live market data: IB Gateway native API");
            return ibGatewayProvider.get();
        }

        log.warn("Live market data: no active IBKR market-data provider for mode {}, returning no live prices.",
            ibkrProps.getMode());
        return java.util.Map::of;
    }

    @Bean
    @Primary
    public HistoricalDataProvider primaryHistoricalProvider(Optional<IbkrHistoricalProvider> ibkrHistorical,
                                                            Optional<IbGatewayHistoricalProvider> ibGatewayHistorical,
                                                            IbkrProperties ibkrProps) {
        if (ibkrProps.isEnabled() && ibkrProps.getMode() == IbkrBackendMode.CLIENT_PORTAL && ibkrHistorical.isPresent()) {
            log.info("Historical data provider: IBKR Client Portal");
            return ibkrHistorical.get();
        }
        if (ibkrProps.isEnabled() && ibkrProps.getMode() == IbkrBackendMode.IB_GATEWAY && ibGatewayHistorical.isPresent()) {
            log.info("Historical data provider: IB Gateway native API");
            return ibGatewayHistorical.get();
        }

        log.warn("Historical data provider: no active IBKR historical provider for mode {}, returning no historical candles.",
            ibkrProps.getMode());
        return new HistoricalDataProvider() {
            @Override
            public java.util.List<com.riskdesk.domain.model.Candle> fetchHistory(
                    com.riskdesk.domain.model.Instrument instrument, String timeframe, int count) {
                return java.util.List.of();
            }

            @Override
            public boolean supports(com.riskdesk.domain.model.Instrument instrument, String timeframe) {
                return false;
            }
        };
    }
}
