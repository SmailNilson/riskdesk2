package com.riskdesk.domain.contract;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Shared utilities for parsing and normalizing IBKR contract month strings.
 *
 * IBKR returns contract months in varying formats: "20260615", "202606", or
 * occasionally with separators. This class centralizes the digit-extraction
 * and parsing logic that was previously duplicated across 4+ files.
 */
public final class ContractMonthUtils {

    private ContractMonthUtils() {}

    /**
     * Extracts a 6-digit YYYYMM contract month from a raw IBKR string.
     * Returns null if the input is null, blank, or contains fewer than 6 digits.
     */
    public static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }

    /**
     * Parses an IBKR contract date/month string into a LocalDate.
     * Handles both YYYYMMDD (exact expiry) and YYYYMM (first day of month) formats.
     * Returns null if unparseable.
     */
    public static LocalDate parseExpiryDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        try {
            if (digits.length() >= 8) {
                return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (digits.length() == 6) {
                return YearMonth.parse(digits, DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
            }
        } catch (Exception e) {
            // Unparseable date string — return null
        }
        return null;
    }

    /**
     * Parses an IBKR contract month string into the last day of that month.
     * Used for conservative expiry estimation when only YYYYMM is available.
     * Returns null if unparseable.
     */
    public static LocalDate parseExpiryDateEndOfMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        try {
            if (digits.length() >= 8) {
                return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (digits.length() == 6) {
                return YearMonth.parse(digits, DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth();
            }
        } catch (Exception e) {
            // Unparseable date string — return null
        }
        return null;
    }
}
