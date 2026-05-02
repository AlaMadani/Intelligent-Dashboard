package com.neo.dashboard.service;

import com.neo.dashboard.dto.*;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserDashboardService {

    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final ActiveAnomalyService activeAnomalyService;
    private final AnomalyEventRepositoryHelper anomalyEventRepositoryHelper;
    private final SessionAnalysisRepositoryHelper sessionAnalysisRepositoryHelper;
    private final AnomalyEventMapper anomalyEventMapper;
    private final SessionAnalysisMapper sessionAnalysisMapper;

    @Transactional(readOnly = true)
    public Optional<UserDashboardDto> getDashboard(String insuredId) {
        UserRiskProfileDto riskProfile = riskProfileService.getRiskProfile(insuredId).orElse(null);
        NextActionPredictionDto nextActions = nextActionPredictionService.getPrediction(insuredId).orElse(null);
        AnomalyAlertDto activeAnomaly = activeAnomalyService.getActiveAnomaly(insuredId).orElse(null);

        List<SessionAnalysisDto> recentSessions = sessionAnalysisRepositoryHelper
                .findTop10ByInsuredId(insuredId).stream()
                .map(sessionAnalysisMapper::toDto)
                .toList();

        List<AnomalyEventDto> recentAnomalies = anomalyEventRepositoryHelper
                .findTop10ByInsuredId(insuredId).stream()
                .map(anomalyEventMapper::toDto)
                .toList();

        if (riskProfile == null && recentSessions.isEmpty() && recentAnomalies.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new UserDashboardDto(
                riskProfile, recentSessions, recentAnomalies, nextActions, activeAnomaly
        ));
    }
}