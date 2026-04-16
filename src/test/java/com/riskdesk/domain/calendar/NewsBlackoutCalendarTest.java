package com.riskdesk.domain.calendar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsBlackoutCalendarTest {

    private static final Instant FOMC_AT = Instant.parse("2026-04-30T18:00:00Z");
    private static final Instant NFP_AT = Instant.parse("2026-05-02T12:30:00Z");

    private static final NewsEvent FOMC = new NewsEvent(FOMC_AT, "FOMC Rate Decision", NewsEvent.Impact.HIGH);
    private static final NewsEvent NFP = new NewsEvent(NFP_AT, "Non-Farm Payrolls", NewsEvent.Impact.HIGH);

    @Test
    void disabled_factoryHasEmptyEventsAndZeroWindows() {
        NewsBlackoutCalendar cal = NewsBlackoutCalendar.disabled();

        assertThat(cal.isEnabled()).isFalse();
        assertThat(cal.events()).isEmpty();
        assertThat(cal.preEventWindow()).isEqualTo(Duration.ZERO);
        assertThat(cal.postEventWindow()).isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled_neverReportsActiveBlackout_evenAtExactEventTimestamp() {
        NewsBlackoutCalendar cal = NewsBlackoutCalendar.disabled();

        assertThat(cal.activeBlackout(FOMC_AT)).isEmpty();
        assertThat(cal.isInBlackout(FOMC_AT)).isFalse();
    }

    @Test
    void enabledWithEvents_butDisabledFlag_stillReturnsEmpty() {
        NewsBlackoutCalendar cal = new NewsBlackoutCalendar(
            false,
            List.of(FOMC),
            Duration.ofMinutes(30),
            Duration.ofMinutes(15)
        );

        assertThat(cal.activeBlackout(FOMC_AT)).isEmpty();
        assertThat(cal.isInBlackout(FOMC_AT)).isFalse();
    }

    @Test
    void enabled_beforePreWindow_notInBlackout() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        Instant wayBefore = FOMC_AT.minus(Duration.ofHours(2));
        assertThat(cal.activeBlackout(wayBefore)).isEmpty();
    }

    @Test
    void enabled_atPreWindowStart_isInBlackout_inclusive() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        Instant windowStart = FOMC_AT.minus(Duration.ofMinutes(30));
        Optional<NewsEvent> active = cal.activeBlackout(windowStart);

        assertThat(active).contains(FOMC);
    }

    @Test
    void enabled_insideWindow_returnsEvent() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        Instant midWindow = FOMC_AT.minus(Duration.ofMinutes(5));
        assertThat(cal.activeBlackout(midWindow)).contains(FOMC);
        assertThat(cal.isInBlackout(midWindow)).isTrue();
    }

    @Test
    void enabled_atEventTimestamp_isInBlackout() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        assertThat(cal.activeBlackout(FOMC_AT)).contains(FOMC);
    }

    @Test
    void enabled_atPostWindowEnd_isInBlackout_inclusive() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        Instant windowEnd = FOMC_AT.plus(Duration.ofMinutes(15));
        assertThat(cal.activeBlackout(windowEnd)).contains(FOMC);
    }

    @Test
    void enabled_afterPostWindow_notInBlackout() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        Instant wayAfter = FOMC_AT.plus(Duration.ofMinutes(16));
        assertThat(cal.activeBlackout(wayAfter)).isEmpty();
    }

    @Test
    void multipleEvents_returnsFirstMatching() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC, NFP), 30, 15);

        Instant duringFomc = FOMC_AT.minus(Duration.ofMinutes(5));
        Instant duringNfp = NFP_AT.plus(Duration.ofMinutes(5));

        assertThat(cal.activeBlackout(duringFomc)).contains(FOMC);
        assertThat(cal.activeBlackout(duringNfp)).contains(NFP);

        Instant quietGap = Instant.parse("2026-05-01T10:00:00Z");
        assertThat(cal.activeBlackout(quietGap)).isEmpty();
    }

    @Test
    void zeroWindows_onlyExactTimestampMatches() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 0, 0);

        assertThat(cal.activeBlackout(FOMC_AT)).contains(FOMC);
        assertThat(cal.activeBlackout(FOMC_AT.minusSeconds(1))).isEmpty();
        assertThat(cal.activeBlackout(FOMC_AT.plusSeconds(1))).isEmpty();
    }

    @Test
    void construct_nullEvents_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsBlackoutCalendar(true, null, Duration.ZERO, Duration.ZERO))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("events");
    }

    @Test
    void construct_nullPreWindow_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsBlackoutCalendar(true, List.of(), null, Duration.ZERO))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("preEventWindow");
    }

    @Test
    void construct_nullPostWindow_throwsNpeWithArgumentName() {
        assertThatThrownBy(() -> new NewsBlackoutCalendar(true, List.of(), Duration.ZERO, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("postEventWindow");
    }

    @Test
    void construct_negativePreWindow_throwsIllegalArgument() {
        assertThatThrownBy(() -> new NewsBlackoutCalendar(
            true, List.of(), Duration.ofMinutes(-1), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("preEventWindow");
    }

    @Test
    void construct_negativePostWindow_throwsIllegalArgument() {
        assertThatThrownBy(() -> new NewsBlackoutCalendar(
            true, List.of(), Duration.ZERO, Duration.ofMinutes(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("postEventWindow");
    }

    @Test
    void activeBlackout_nullNow_throwsNpeWithArgumentName() {
        NewsBlackoutCalendar cal = enabledCalendar(List.of(FOMC), 30, 15);

        assertThatThrownBy(() -> cal.activeBlackout(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("now");
    }

    @Test
    void eventsList_isDefensivelyCopied_mutatingInputDoesNotAffectCalendar() {
        java.util.ArrayList<NewsEvent> mutable = new java.util.ArrayList<>();
        mutable.add(FOMC);
        NewsBlackoutCalendar cal = new NewsBlackoutCalendar(
            true, mutable, Duration.ofMinutes(30), Duration.ofMinutes(15));

        mutable.clear();

        assertThat(cal.events()).containsExactly(FOMC);
        assertThat(cal.activeBlackout(FOMC_AT)).contains(FOMC);
    }

    private static NewsBlackoutCalendar enabledCalendar(List<NewsEvent> events, int preMin, int postMin) {
        return new NewsBlackoutCalendar(
            true,
            events,
            Duration.ofMinutes(preMin),
            Duration.ofMinutes(postMin)
        );
    }
}
