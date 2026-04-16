package com.riskdesk.domain.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsEventTest {

    @Test
    void construct_validFields_storesAllValues() {
        Instant when = Instant.parse("2026-04-30T18:00:00Z");
        NewsEvent event = new NewsEvent(when, "FOMC Rate Decision", NewsEvent.Impact.HIGH);

        assertThat(event.timestamp()).isEqualTo(when);
        assertThat(event.name()).isEqualTo("FOMC Rate Decision");
        assertThat(event.impact()).isEqualTo(NewsEvent.Impact.HIGH);
    }

    @Test
    void construct_nullTimestamp_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsEvent(null, "FOMC", NewsEvent.Impact.HIGH))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("timestamp");
    }

    @Test
    void construct_nullName_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsEvent(Instant.now(), null, NewsEvent.Impact.HIGH))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("name");
    }

    @Test
    void construct_nullImpact_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsEvent(Instant.now(), "FOMC", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("impact");
    }

    @Test
    void construct_blankName_throwsIllegalArgument() {
        assertThatThrownBy(() -> new NewsEvent(Instant.now(), "   ", NewsEvent.Impact.HIGH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }
}
