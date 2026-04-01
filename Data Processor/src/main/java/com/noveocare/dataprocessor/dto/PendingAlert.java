package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAlert {
    private AnomalyAlert alert;
    private String rawEventJson;
}
