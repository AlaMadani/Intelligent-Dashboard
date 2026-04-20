package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated anomaly investigation payload for the deep-dive screen.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyInvestigationDto {

    private Long anomalyEventId;
    private AnomalyEventDto anomaly;
    private SessionAnalysisDto sessionAnalysis;
    private ActiveSessionDto liveSession;
    private UserRiskProfileDto riskProfile;
    private NextActionPredictionDto nextActions;
    private AnomalyAlertDto activeAnomaly;
}
