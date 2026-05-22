package com.riskdesk.domain.engine.strategy.playbook;

/**
 * Strategy profile for the autonomous Playbook engine.
 * Controls the checklist score threshold and trailing exit / max daily loss rules.
 */
public enum PlaybookProfile {
    /** Raw checklist score >= 5/7 executes immediately. No ATR exits or daily max loss block. */
    BASELINE,
    /** Raw checklist score >= 5/7 executes immediately. Enables ATR exits + daily max loss block. */
    SESSION_ATR,
    /** Raw checklist score >= 6/7 executes immediately. Enables ATR exits + daily max loss block. */
    STRICT;

    public boolean blocksOnMaxLoss() {
        return this != BASELINE;
    }

    public boolean requiresAtrExits() {
        return this != BASELINE;
    }

    public int minScore() {
        return this == STRICT ? 6 : 5;
    }
}
