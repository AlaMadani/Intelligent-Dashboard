package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class NextActionScore {
    String action;
    double probability;
}
