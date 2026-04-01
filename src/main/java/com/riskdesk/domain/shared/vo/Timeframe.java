package com.riskdesk.domain.shared.vo;

/**
 * Enum representing supported trading timeframes.
 */
public enum Timeframe {

    M1("1m", 1),
    M5("5m", 5),
    M10("10m", 10),
    M30("30m", 30),
    H1("1h", 60),
    H4("4h", 240),
    D1("1d", 1440);

    private final String label;
    private final int minutes;

    Timeframe(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String label() {
        return label;
    }

    public int minutes() {
        return minutes;
    }

    public int periodSeconds() {
        return minutes * 60;
    }

    /**
     * Look up a Timeframe by its label string (e.g. "1h", "10m").
     *
     * @throws IllegalArgumentException if no matching timeframe is found
     */
    public static Timeframe fromLabel(String label) {
        if (label == null) {
            throw new IllegalArgumentException("Timeframe label must not be null");
        }
        for (Timeframe tf : values()) {
            if (tf.label.equals(label)) {
                return tf;
            }
        }
        throw new IllegalArgumentException("Unknown timeframe label: " + label);
    }
}
