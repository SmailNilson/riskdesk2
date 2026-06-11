package com.riskdesk.application.dto;

import java.util.List;

/**
 * Session volume profile payload for GET /api/order-flow/volume-profile/{instrument}.
 *
 * @param instrument   instrument code (e.g. MNQ)
 * @param session      current RTH session — developing while inside 09:30–16:00 ET
 * @param priorSession most recent completed RTH session before {@code session}; null when no data
 * @param overnight    overnight/Globex session (18:00 ET reopen → RTH open); null when no data
 * @param nakedPocs    prior-session POCs not yet revisited by any later session, oldest first
 */
public record VolumeProfileView(
    String instrument,
    SessionProfileView session,
    SessionProfileView priorSession,
    SessionProfileView overnight,
    List<NakedPocView> nakedPocs
) {
    /**
     * One session's profile. Prices are bucket lower bounds on the configured grid.
     *
     * @param date        ET trading date of the session (ISO yyyy-MM-dd)
     * @param poc         Point of Control — highest-volume price bucket
     * @param vah         Value Area High (70% value area)
     * @param val         Value Area Low (70% value area)
     * @param totalVolume total contracts traded in the session window
     * @param developing  true while the session is still in progress
     */
    public record SessionProfileView(
        String date,
        Double poc,
        Double vah,
        Double val,
        long totalVolume,
        boolean developing
    ) {}

    /** A naked (virgin) POC: untouched by any later session's range. */
    public record NakedPocView(double price, String date) {}
}
