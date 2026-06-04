package com.riskdesk.application.service;

import com.riskdesk.domain.execution.MarketableExecutionSettings;
import com.riskdesk.domain.execution.port.MarketableSettingsRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketableExecutionSettingsServiceTest {

    /** Minimal in-memory store. */
    private static final class FakeStore implements MarketableSettingsRepositoryPort {
        MarketableExecutionSettings stored;
        boolean throwOnLoad;
        int saves;

        @Override public Optional<MarketableExecutionSettings> load() {
            if (throwOnLoad) {
                throw new RuntimeException("db down");
            }
            return Optional.ofNullable(stored);
        }

        @Override public MarketableExecutionSettings save(MarketableExecutionSettings s) {
            stored = s;
            saves++;
            return s;
        }
    }

    /** Service seeded with config defaults close=ON, reverseOpen=ON, cross=10. */
    private MarketableExecutionSettingsService service(FakeStore store) {
        return new MarketableExecutionSettingsService(store, true, true, 10);
    }

    @Test
    void usesConfiguredDefaults_whenNothingPersisted() {
        MarketableExecutionSettings s = service(new FakeStore()).current();
        assertThat(s.closeEnabled()).isTrue();
        assertThat(s.reverseOpenEnabled()).isTrue();
        assertThat(s.crossTicks()).isEqualTo(10);
    }

    @Test
    void prefersPersistedValueOverDefaults() {
        FakeStore store = new FakeStore();
        store.stored = new MarketableExecutionSettings(false, false, 4);
        MarketableExecutionSettings s = service(store).current();
        assertThat(s.closeEnabled()).isFalse();
        assertThat(s.reverseOpenEnabled()).isFalse();
        assertThat(s.crossTicks()).isEqualTo(4);
    }

    @Test
    void update_partial_keepsUnsetFields_persists_andRefreshesCache() {
        FakeStore store = new FakeStore();
        MarketableExecutionSettingsService svc = service(store);

        MarketableExecutionSettings after = svc.update(false, null, null); // only the close toggle

        assertThat(after.closeEnabled()).isFalse();
        assertThat(after.reverseOpenEnabled()).isTrue();  // unchanged
        assertThat(after.crossTicks()).isEqualTo(10);     // unchanged
        assertThat(store.saves).isEqualTo(1);
        assertThat(svc.current()).isEqualTo(after);       // cache refreshed, no reload needed
    }

    @Test
    void update_crossTicksZero_isAllowed() {
        assertThat(service(new FakeStore()).update(null, null, 0).crossTicks()).isEqualTo(0);
    }

    @Test
    void update_rejectsCrossTicksAboveCap() {
        assertThatThrownBy(() -> service(new FakeStore()).update(null, null, 5000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_rejectsNegativeCrossTicks() {
        assertThatThrownBy(() -> service(new FakeStore()).update(null, null, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void current_fallsBackToDefaults_whenLoadThrows() {
        FakeStore store = new FakeStore();
        store.throwOnLoad = true;
        assertThat(service(store).current().crossTicks()).isEqualTo(10); // defaults, no crash
    }

    @Test
    void current_transientLoadFailure_doesNotCacheDefaults_servesPersistedOnRecovery() {
        FakeStore store = new FakeStore();
        store.stored = new MarketableExecutionSettings(false, false, 4); // operator previously disabled exits
        store.throwOnLoad = true;                                        // first load fails transiently
        MarketableExecutionSettingsService svc = service(store);

        assertThat(svc.current().closeEnabled()).isTrue();  // serves defaults (no crash) — must NOT cache them

        store.throwOnLoad = false;                          // DB recovers

        // The next read re-loads and serves the PERSISTED operator setting — proving defaults weren't cached.
        assertThat(svc.current().closeEnabled()).isFalse();
        assertThat(svc.current().crossTicks()).isEqualTo(4);
    }

    @Test
    void update_duringTransientLoadFailure_doesNotClobberPersisted_andPropagates() {
        FakeStore store = new FakeStore();
        store.stored = new MarketableExecutionSettings(false, false, 4); // operator previously persisted
        store.throwOnLoad = true;                                        // cache empty + load fails on the PUT
        MarketableExecutionSettingsService svc = service(store);

        // A partial PUT (only closeEnabled) must NOT merge omitted fields from defaults and overwrite the
        // persisted policy — it propagates (PUT fails → operator retries) and saves nothing.
        assertThatThrownBy(() -> svc.update(true, null, null)).isInstanceOf(RuntimeException.class);
        assertThat(store.saves).isEqualTo(0);
        assertThat(store.stored.reverseOpenEnabled()).isFalse(); // persisted policy intact
        assertThat(store.stored.crossTicks()).isEqualTo(4);
    }
}
