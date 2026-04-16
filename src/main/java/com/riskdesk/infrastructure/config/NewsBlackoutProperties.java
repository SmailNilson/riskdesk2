package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.calendar.NewsEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the scheduled news blackout calendar
 * (FOMC, NFP, CPI, ECB …) applied to alerts by
 * {@code SignalPreFilterService} as Rule 6.
 *
 * <p>Binds to {@code riskdesk.news-blackout.*} in application properties:
 * <pre>
 *   riskdesk.news-blackout.enabled=true
 *   riskdesk.news-blackout.pre-event-minutes=30
 *   riskdesk.news-blackout.post-event-minutes=15
 *   riskdesk.news-blackout.events[0].timestamp=2026-04-30T18:00:00Z
 *   riskdesk.news-blackout.events[0].name=FOMC Rate Decision
 *   riskdesk.news-blackout.events[0].impact=HIGH
 * </pre>
 * When {@code enabled=false} the calendar is still instantiated but
 * {@code activeBlackout} always returns empty.
 */
@ConfigurationProperties(prefix = "riskdesk.news-blackout")
public class NewsBlackoutProperties {

    /** Master switch — when false, Rule 6 is a no-op regardless of events. */
    private boolean enabled = false;

    /** Window before an event's timestamp during which signals are blocked. */
    private int preEventMinutes = 30;

    /** Window after an event's timestamp during which signals are blocked. */
    private int postEventMinutes = 15;

    /** Calendar of scheduled events. */
    private List<Event> events = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPreEventMinutes() {
        return preEventMinutes;
    }

    public void setPreEventMinutes(int preEventMinutes) {
        this.preEventMinutes = preEventMinutes;
    }

    public int getPostEventMinutes() {
        return postEventMinutes;
    }

    public void setPostEventMinutes(int postEventMinutes) {
        this.postEventMinutes = postEventMinutes;
    }

    public List<Event> getEvents() {
        return events;
    }

    public void setEvents(List<Event> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }

    /** Settable DTO used by Spring Boot property binding; converted to a domain {@link NewsEvent}. */
    public static class Event {
        private Instant timestamp;
        private String name;
        private NewsEvent.Impact impact = NewsEvent.Impact.HIGH;

        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public NewsEvent.Impact getImpact() { return impact; }
        public void setImpact(NewsEvent.Impact impact) { this.impact = impact; }

        NewsEvent toDomain() {
            return new NewsEvent(timestamp, name, impact);
        }
    }
}
