package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Generic DTO for raw API-statistics responses where the payload shape is
 * not known at compile time (accepts any {@link Object}). Used primarily
 * by external / third-party data sources.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsApiResponseDto {
    /* Calendar date the statistics entry relates to. */
    private LocalDate date;
    /* Identifier of the system or module that produced the data. */
    private String source;
    /* Arbitrary payload – the receiver should cast or inspect the type. */
    private Object payload;
}
