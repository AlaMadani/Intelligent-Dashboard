package com.noveocare.dataprocessor.ai;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ForecastSeriesPoint {
    String ds;
    Double yhat;
    Double yhatLower;
    Double yhatUpper;
    Double trend;
}
