package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * Wrapper used by internal statistics endpoints and SSE event streams.
 * Carries a flexible {@link JsonNode} payload so charts and tables
 * downstream can consume it without knowing the exact schema at compile
 * time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponseDto {
    /* Date the statistics entry covers. */
    private LocalDate date;
    /* Name of the originating system or module (e.g. "KPI", "RACH", "MOBILITY"). */
    private String source;

    /* Semi-structured JSON data consumable by frontend charting components. */
    private JsonNode payload;
}
