package io.github.arsalanrc.fanout.integration;

import java.util.Map;

/**
 * IATA equipment codes, spelled out.
 *
 * <p>Amadeus sends {@code "738"} where a traveller wants "Boeing 737-800", and
 * a page showing the code has passed a normalisation job to the reader. It
 * belongs here for the same reason the currency and timezone handling does:
 * this module exists to absorb what suppliers say and hand the rest of the
 * system one vocabulary.
 *
 * <p><b>The aircraft are the one part of this project that is not invented.</b>
 * Every carrier here is fictional, deliberately, because putting made-up prices
 * beside a real airline's name would eventually be read as that airline's
 * charges. Aircraft types carry no such risk: a 737-800 on a short European
 * route is a fact about the route, not a claim about anybody's fares. So the
 * fictional carriers fly the types that really do work these routes.
 *
 * <p>Unknown codes come back as the code. Inventing a name for equipment
 * nobody recognises would be worse than showing what the supplier sent.
 */
public final class Aircraft {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("738", "Boeing 737-800"),
            Map.entry("7M8", "Boeing 737 MAX 8"),
            Map.entry("73H", "Boeing 737-800"),
            Map.entry("319", "Airbus A319"),
            Map.entry("320", "Airbus A320"),
            Map.entry("32N", "Airbus A320neo"),
            Map.entry("321", "Airbus A321"),
            Map.entry("32Q", "Airbus A321neo"),
            Map.entry("E90", "Embraer E190"),
            Map.entry("E95", "Embraer E195"),
            Map.entry("290", "Embraer E190-E2"),
            Map.entry("295", "Embraer E195-E2"),
            Map.entry("AT7", "ATR 72"),
            Map.entry("DH4", "De Havilland Dash 8-400"));

    private Aircraft() {
    }

    /** The name for an equipment code, or null when the supplier sent none. */
    public static String name(Json code) {
        if (code == null || code.isMissing()) return null;
        return name(code.text());
    }

    public static String name(String code) {
        if (code == null || code.isBlank()) return null;
        return NAMES.getOrDefault(code.trim().toUpperCase(java.util.Locale.ROOT), code.trim());
    }
}
