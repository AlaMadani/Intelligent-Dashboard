package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnomalyTypeResult {
    private String type;
    private double confidence;
}
