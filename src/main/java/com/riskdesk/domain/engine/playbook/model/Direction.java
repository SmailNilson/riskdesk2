package com.riskdesk.domain.engine.playbook.model;

public enum Direction {
    LONG, SHORT;

    public Direction opposite() {
        return this == LONG ? SHORT : LONG;
    }
}
