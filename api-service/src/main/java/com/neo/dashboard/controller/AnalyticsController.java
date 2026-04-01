package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.dto.AnomalyExplanationDto;
import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.dto.PaginationMeta;
import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.service.ActiveAnomalyService;
import com.neo.dashboard.service.AnomalyAlertStreamService;
import com.neo.dashboard.service.AnomalyEventService;
import com.neo.dashboard.service.AnomalyExplanationService;
import com.neo.dashboard.service.NextActionPredictionService;
import com.neo.dashboard.service.RiskProfileService;
import com.neo.dashboard.service.SessionAnalysisService;
import com.neo.dashboard.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalyticsController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final SessionAnalysisService sessionAnalysisService;
    private final AnomalyEventService anomalyEventService;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final StatsService statsService;
    private final ActiveAnomalyService activeAnomalyService;
    private final AnomalyExplanationService anomalyExplanationService;
    private final AnomalyAlertStreamService anomalyAlertStreamService;

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionAnalysisDto>>> listSessions(
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Boolean isAnomaly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "startTime")
        );
        Page<SessionAnalysisDto> result = sessionAnalysisService.search(insuredId, from, to, isAnomaly, pageable);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<SessionAnalysisDto>> getSession(@PathVariable Long id) {
        return sessionAnalysisService.getById(id)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/anomaly-events")
    public ResponseEntity<ApiResponse<List<AnomalyEventDto>>> listAnomalyEvents(
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "eventTime")
        );
        Page<AnomalyEventDto> result = anomalyEventService.search(insuredId, from, to, tier, type, pageable);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    @GetMapping("/anomaly-events/{id}")
    public ResponseEntity<ApiResponse<AnomalyEventDto>> getAnomalyEvent(@PathVariable Long id) {
        return anomalyEventService.getById(id)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/anomaly-events/{id}/explanation")
    public ResponseEntity<ApiResponse<AnomalyExplanationDto>> explainAnomalyEvent(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return anomalyExplanationService.explain(id, refresh)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/risk/{insuredId}")
    public ResponseEntity<ApiResponse<UserRiskProfileDto>> getRiskProfile(@PathVariable String insuredId) {
        return riskProfileService.getRiskProfile(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/next-actions/{insuredId}")
    public ResponseEntity<ApiResponse<NextActionPredictionDto>> getNextActions(@PathVariable String insuredId) {
        return nextActionPredictionService.getPrediction(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/stats/live")
    public ResponseEntity<ApiResponse<StatsResponseDto>> getLiveStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(statsService.getLiveStats(date)));
    }

    @GetMapping("/stats/trend")
    public ResponseEntity<ApiResponse<StatsResponseDto>> getTrendStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(statsService.getTrendStats(date)));
    }

    @GetMapping("/anomaly/active/{insuredId}")
    public ResponseEntity<ApiResponse<AnomalyAlertDto>> getActiveAnomaly(@PathVariable String insuredId) {
        return activeAnomalyService.getActiveAnomaly(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/stream/anomalies", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnomalies() {
        return anomalyAlertStreamService.subscribe();
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
