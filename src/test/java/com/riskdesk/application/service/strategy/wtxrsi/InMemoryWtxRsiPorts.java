package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiStrategyStatePort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory port implementations used by the orchestrator tests. */
final class InMemoryWtxRsiPorts {

    static final class State implements WtxRsiStrategyStatePort {
        final Map<String, WtxRsiStrategyState> store = new HashMap<>();

        @Override
        public Optional<WtxRsiStrategyState> load(String instrument, String timeframe) {
            return Optional.ofNullable(store.get(key(instrument, timeframe)));
        }

        @Override
        public void save(WtxRsiStrategyState state) {
            store.put(key(state.instrument(), state.timeframe()), state);
        }

        @Override
        public List<WtxRsiStrategyState> findAllOpen() {
            return store.values().stream()
                    .filter(s -> s.currentPosition() != WtxRsiPosition.FLAT)
                    .toList();
        }

        private static String key(String instrument, String timeframe) {
            return instrument + ":" + timeframe;
        }
    }

    static final class History implements WtxRsiSignalHistoryPort {
        final List<WtxRsiSignalRecord> store = new ArrayList<>();

        @Override
        public void save(WtxRsiSignalRecord record) {
            store.add(record);
        }

        @Override
        public List<WtxRsiSignalRecord> findRecent(String instrument, int limit) {
            return store.stream()
                    .filter(r -> r.instrument().equals(instrument))
                    .sorted(Comparator.comparing(WtxRsiSignalRecord::signalTs).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<WtxRsiSignalRecord> findRecent(String instrument, String timeframe, int limit) {
            return store.stream()
                    .filter(r -> r.instrument().equals(instrument) && r.timeframe().equals(timeframe))
                    .sorted(Comparator.comparing(WtxRsiSignalRecord::signalTs).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    private InMemoryWtxRsiPorts() {}
}
