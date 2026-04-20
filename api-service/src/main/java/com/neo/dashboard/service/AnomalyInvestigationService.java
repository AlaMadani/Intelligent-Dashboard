package com.neo.dashboard.service;

import com.neo.dashboard.dto.ActiveSessionDto;
import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.dto.AnomalyInvestigationDto;
import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Aggregates all SQL and Redis context needed for an anomaly deep-dive page.
 */
@Service
@RequiredArgsConstructor
public class AnomalyInvestigationService {

    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventMapper anomalyEventMapper;
    private final SessionAnalysisMapper sessionAnalysisMapper;
    private final ActiveSessionService activeSessionService;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final ActiveAnomalyService activeAnomalyService;

    @Transactional(readOnly = true)
    public Optional<AnomalyInvestigationDto> getInvestigation(Long anomalyEventId) {
        return anomalyEventRepository.findById(anomalyEventId)
                .map(anomaly -> {
                    AnomalyEventDto anomalyDto = anomalyEventMapper.toDto(anomaly);
                    SessionAnalysisDto sessionAnalysis = sessionAnalysisRepository
                            .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                            .map(sessionAnalysisMapper::toDto)
                            .orElse(null);
                    ActiveSessionDto liveSession = activeSessionService
                            .getSessionInsight(anomaly.getInsuredId(), anomaly.getSessionId())
                            .orElse(null);
                    UserRiskProfileDto riskProfile = riskProfileService.getRiskProfile(anomaly.getInsuredId()).orElse(null);
                    NextActionPredictionDto nextActions = nextActionPredictionService.getPrediction(anomaly.getInsuredId()).orElse(null);
                    AnomalyAlertDto activeAnomaly = activeAnomalyService.getActiveAnomaly(anomaly.getInsuredId()).orElse(null);
                    return new AnomalyInvestigationDto(
                            anomalyEventId,
                            anomalyDto,
                            sessionAnalysis,
                            liveSession,
                            riskProfile,
                            nextActions,
                            activeAnomaly
                    );
                });
    }
}
