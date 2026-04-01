package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

/* DTO for live/trend stats payloads. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponseDto {
    private LocalDate date;
    private String source;
    private JsonNode payload;
}
