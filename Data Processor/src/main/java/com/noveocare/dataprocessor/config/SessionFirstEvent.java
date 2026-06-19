package com.noveocare.dataprocessor.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionFirstEvent {
    private String insuredId;
    private String sessionId;
    private Instant firstEventTimestamp;
    private Integer firstSequenceInSession;
    private String firstIp;
    private String firstDevice;
    private String firstAction;
    private String firstRoute;
    private String firstCountry;
    private String firstStatus;
    private int firstHour;
    private int firstDayOfWeek;

    public boolean isAvailable() {
        return firstEventTimestamp != null;
    }
}