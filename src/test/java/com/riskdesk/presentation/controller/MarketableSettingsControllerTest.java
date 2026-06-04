package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.MarketableSettingsUpdateRequest;
import com.riskdesk.application.dto.MarketableSettingsView;
import com.riskdesk.application.service.MarketableExecutionSettingsService;
import com.riskdesk.domain.execution.MarketableExecutionSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lightweight Mockito test (no Spring context) — the controller forwards directly to
 * {@link MarketableExecutionSettingsService}, so verifying delegation + HTTP shape is sufficient.
 */
class MarketableSettingsControllerTest {

    private final MarketableExecutionSettingsService service = mock(MarketableExecutionSettingsService.class);
    private final MarketableSettingsController controller = new MarketableSettingsController(service);

    @Test
    void get_returnsCurrentSettings() {
        when(service.current()).thenReturn(new MarketableExecutionSettings(true, false, 8));

        MarketableSettingsView view = controller.get();

        assertThat(view.closeEnabled()).isTrue();
        assertThat(view.reverseOpenEnabled()).isFalse();
        assertThat(view.crossTicks()).isEqualTo(8);
    }

    @Test
    void update_returnsUpdatedSettings() {
        when(service.update(false, null, null)).thenReturn(new MarketableExecutionSettings(false, true, 10));

        ResponseEntity<?> resp = controller.update(new MarketableSettingsUpdateRequest(false, null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isInstanceOf(MarketableSettingsView.class);
        assertThat(((MarketableSettingsView) resp.getBody()).closeEnabled()).isFalse();
    }

    @Test
    void update_invalidValue_returns400() {
        when(service.update(any(), any(), any())).thenThrow(new IllegalArgumentException("crossTicks out of range"));

        ResponseEntity<?> resp = controller.update(new MarketableSettingsUpdateRequest(null, null, 9999));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_nullBody_isANoOpUpdate() {
        when(service.update(null, null, null)).thenReturn(new MarketableExecutionSettings(true, true, 10));

        ResponseEntity<?> resp = controller.update(null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
