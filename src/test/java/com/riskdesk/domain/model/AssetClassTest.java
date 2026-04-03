package com.riskdesk.domain.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssetClassTest {

    @Test
    void instrumentAssetClassMapping() {
        assertEquals(AssetClass.ENERGY, Instrument.MCL.assetClass());
        assertEquals(AssetClass.METALS, Instrument.MGC.assetClass());
        assertEquals(AssetClass.FOREX, Instrument.E6.assetClass());
        assertEquals(AssetClass.EQUITY_INDEX, Instrument.MNQ.assetClass());
        assertNull(Instrument.DXY.assetClass());
    }

    @Test
    void metalsRelevantCorrelations() {
        var correlations = AssetClass.METALS.relevantCorrelations();
        assertTrue(correlations.contains(AssetClass.CorrelationKind.DXY));
        assertTrue(correlations.contains(AssetClass.CorrelationKind.US10Y));
        assertTrue(correlations.contains(AssetClass.CorrelationKind.SECTOR_LEADER));
        assertFalse(correlations.contains(AssetClass.CorrelationKind.VIX));
    }

    @Test
    void equityIndexRelevantCorrelations() {
        var correlations = AssetClass.EQUITY_INDEX.relevantCorrelations();
        assertTrue(correlations.contains(AssetClass.CorrelationKind.DXY));
        assertTrue(correlations.contains(AssetClass.CorrelationKind.VIX));
        assertTrue(correlations.contains(AssetClass.CorrelationKind.US10Y));
        assertTrue(correlations.contains(AssetClass.CorrelationKind.SECTOR_LEADER));
    }

    @Test
    void sectorLeaderMapping() {
        assertEquals("SI", AssetClass.METALS.sectorLeaderSymbol().orElse(null));
        assertEquals("ES", AssetClass.EQUITY_INDEX.sectorLeaderSymbol().orElse(null));
        assertTrue(AssetClass.ENERGY.sectorLeaderSymbol().isEmpty());
        assertTrue(AssetClass.FOREX.sectorLeaderSymbol().isEmpty());
    }
}
