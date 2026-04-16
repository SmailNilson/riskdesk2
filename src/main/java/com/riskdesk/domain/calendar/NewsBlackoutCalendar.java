package com.riskdesk.domain.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-domain calendar of scheduled high-impact news events (FOMC / NFP / CPI / ECB …)
 * around which the signal pre-filter is expected to suppress all new entries.
 *
 * <p>A {@link NewsEvent} contributes an active blackout window:
 * <pre>
 *   [event.timestamp - preEventWindow, event.timestamp + postEventWindow]
 * </pre>
 * {@link #activeBlackout(Instant)} returns the first event whose window currently
 * contains {@code now}; callers block execution while the window is active.
 *
 * <p>The calendar is immutable — events and windows are validated and defensively
 * copied in the constructor. Domain layer only: no Spring, no JPA, no infrastructure.
 *
 * <p>Configuration is loaded externally (see
 * {@code infrastructure/config/NewsBlackoutProperties}) and bound at startup via
 * {@code AlertConfig}. When the feature is globally disabled the calendar is still
 * instantiated but {@link #activeBlackout(Instant)} always returns empty.
 */
public final class NewsBlackoutCalendar {

    private final boolean enabled;
    private final List<NewsEvent> events;
    private final Duration preEventWindow;
    private final Duration postEventWindow;

    public NewsBlackoutCalendar(boolean enabled,
                                List<NewsEvent> events,
                                Duration preEventWindow,
                                Duration postEventWindow) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(preEventWindow, "preEventWindow must not be null");
        Objects.requireNonNull(postEventWindow, "postEventWindow must not be null");
        if (preEventWindow.isNegative()) {
            throw new IllegalArgumentException("preEventWindow must not be negative, got " + preEventWindow);
        }
        if (postEventWindow.isNegative()) {
            throw new IllegalArgumentException("postEventWindow must not be negative, got " + postEventWindow);
        }
        this.enabled = enabled;
        this.events = List.copyOf(events);
        this.preEventWindow = preEventWindow;
        this.postEventWindow = postEventWindow;
    }

    /**
     * Factory for the default no-op calendar — useful in tests and when the
     * feature is globally disabled. Never reports an active blackout.
     */
    public static NewsBlackoutCalendar disabled() {
        return new NewsBlackoutCalendar(false, List.of(), Duration.ZERO, Duration.ZERO);
    }

    /**
     * @return the first event whose blackout window contains {@code now},
     *         or empty if the calendar is disabled / empty / no window is active.
     */
    public Optional<NewsEvent> activeBlackout(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!enabled || events.isEmpty()) {
            return Optional.empty();
        }
        for (NewsEvent event : events) {
            if (isWithinBlackoutWindow(now, event)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    /** Convenience predicate: true iff {@link #activeBlackout(Instant)} is present. */
    public boolean isInBlackout(Instant now) {
        return activeBlackout(now).isPresent();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<NewsEvent> events() {
        return events;
    }

    public Duration preEventWindow() {
        return preEventWindow;
    }

    public Duration postEventWindow() {
        return postEventWindow;
    }

    private boolean isWithinBlackoutWindow(Instant now, NewsEvent event) {
        Instant windowStart = event.timestamp().minus(preEventWindow);
        Instant windowEnd = event.timestamp().plus(postEventWindow);
        // Inclusive on both edges — a signal arriving exactly at window start or end
        // is considered inside the blackout.
        return !now.isBefore(windowStart) && !now.isAfter(windowEnd);
    }
}
