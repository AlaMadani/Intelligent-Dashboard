package com.noveocare.dataprocessor.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Value object capturing the first event of a user session.
 * Fields include metadata such as IP, device, action, route, country, status,
 * as well as derived values (hour, day-of-week) for feature engineering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionFirstEvent {
    /* --- Session identifiers --- */
    private String insuredId;
    private String sessionId;
    /* --- First event timestamp --- */
    private Instant firstEventTimestamp;
    private Integer firstSequenceInSession;
    /* --- First event contextual metadata --- */
    private String firstIp;
    private String firstDevice;
    private String firstAction;
    private String firstRoute;
    private String firstCountry;
    private String firstStatus;
    /* --- Derived temporal features --- */
    private int firstHour;
    private int firstDayOfWeek;

    /**
     * Returns true when the first event timestamp has been populated.
     */
    public boolean isAvailable() {
        return firstEventTimestamp != null;
    }
}