package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDashboardDto {
    private UserRiskProfileDto riskProfile;
    private List<SessionAnalysisDto> recentSessions;
    private List<AnomalyEventDto> recentAnomalies;
    private NextActionPredictionDto nextActions;
    private AnomalyAlertDto activeAnomaly;
}