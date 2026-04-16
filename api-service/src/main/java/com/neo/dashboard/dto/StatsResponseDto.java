package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * Wrapper used by stats endpoints and SSE streams so the payload can stay
 * flexible while still carrying source metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponseDto {
    /* Date represented by the payload and the system that produced it. */
    private LocalDate date;
    private String source;

    /* Flexible JSON payload for charts, tables, or cache-backed snapshots. */
    private JsonNode payload;
}
