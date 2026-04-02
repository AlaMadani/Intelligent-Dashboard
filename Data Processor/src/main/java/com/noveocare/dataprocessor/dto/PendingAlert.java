package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Couples an alert with the raw payload that caused it when deferred handling is needed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAlert {
    private AnomalyAlert alert;
    private String rawEventJson;
}
