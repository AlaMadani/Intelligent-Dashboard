package com.noveocare.dataprocessor.ai;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Map;

@Getter
@Builder
public class ForecastSeriesPoint {
    private final String ds;
    private final Double yhat;
    private final Double yhatLower;
    private final Double yhatUpper;
    private final Double trend;
    @Singular("metric")
    private final Map<String, Double> metrics;
}
