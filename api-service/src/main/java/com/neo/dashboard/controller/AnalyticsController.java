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
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.service.ActiveAnomalyService;
import com.neo.dashboard.service.AnomalyEventService;
import com.neo.dashboard.service.AnomalyExplanationService;
import com.neo.dashboard.service.DashboardReadService;
import com.neo.dashboard.service.LiveStatsStreamService;
import com.neo.dashboard.service.NextActionPredictionService;
import com.neo.dashboard.service.RiskProfileService;
import com.neo.dashboard.service.SessionAnalysisService;
import com.neo.dashboard.service.SessionInsightReadService;
import com.neo.dashboard.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Exposes read-only analytics endpoints used by the dashboard:
 * paginated queries, detail views, summary widgets, and SSE streams.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalyticsController {

    /* Shared pagination guardrails for list endpoints. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    /* Read services behind each dashboard capability. */
    private final SessionAnalysisService sessionAnalysisService;
    private final AnomalyEventService anomalyEventService;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final StatsService statsService;
    private final ActiveAnomalyService activeAnomalyService;
    private final AnomalyExplanationService anomalyExplanationService;
    private final LiveStatsStreamService liveStatsStreamService;
    private final DashboardReadService dashboardReadService;
    private final SessionInsightReadService sessionInsightReadService;
    private final SessionAnalysisMapper sessionAnalysisMapper;
    private final AnomalyEventMapper anomalyEventMapper;

    /* Returns session analysis rows with optional filters and pagination metadata. */
    @GetMapping("/sessions/risk-scores")
    public ResponseEntity<ApiResponse<List<SessionAnalysisDto>>> listSessions(
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Boolean isAnomaly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Build a safe pageable request so callers cannot ask for negative pages or oversized pages.
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "startTime")
        );

        // Delegate filtering to the service layer and wrap the page in the common API envelope.
        Page<SessionAnalysisDto> result = sessionAnalysisService.search(insuredId, from, to, isAnomaly, pageable)
                .map(sessionAnalysisMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    /* Returns one session analysis record or 404 when it does not exist. */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<SessionAnalysisDto>> getSession(@PathVariable Long id) {
        return sessionAnalysisService.getById(id)
                .map(sessionAnalysisMapper::toDto)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* Returns anomaly events with optional filters and pagination metadata. */
    @GetMapping("/anomalies")
    public ResponseEntity<ApiResponse<List<AnomalyEventDto>>> listAnomalyEvents(
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Sort by event time so the newest alerts appear first in the dashboard.
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "eventTime")
        );

        // Keep response formatting consistent with the sessions endpoint.
        Page<AnomalyEventDto> result = anomalyEventService.search(insuredId, from, to, tier, type, pageable)
                .map(anomalyEventMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    /* Returns one anomaly event or 404 when it does not exist. */
    @GetMapping("/anomalies/{id}")
    public ResponseEntity<ApiResponse<AnomalyEventDto>> getAnomalyEvent(@PathVariable Long id) {
        return anomalyEventService.getById(id)
                .map(anomalyEventMapper::toDto)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* Builds or refreshes an explanation for a single anomaly event. */
    @GetMapping("/anomalies/{id}/explain")
    public CompletableFuture<ResponseEntity<ApiResponse<AnomalyExplanationDto>>> explainAnomalyEvent(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return anomalyExplanationService.explainAsync(id, refresh)
                .thenApply(result -> result
                        .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /* Returns the latest risk summary for one insured user. */
    @GetMapping("/sessions/risk-scores/{insuredId}")
    public ResponseEntity<ApiResponse<UserRiskProfileDto>> getRiskProfile(@PathVariable String insuredId) {
        return riskProfileService.getRiskProfile(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* Returns the predicted next actions for the insured user. */
    @GetMapping("/next-actions/{insuredId}")
    public ResponseEntity<ApiResponse<NextActionPredictionDto>> getNextActions(@PathVariable String insuredId) {
        return nextActionPredictionService.getPrediction(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns a dashboard snapshot from Redis ({@code dashboard:{view}}) written by the Data Processor.
     * Allowed views: alerts, risky-sessions, cluster-mix, drop-offs, path-deviations, forecasts, forecast-series.
     */
    @GetMapping("/dashboard/{view}")
    public ResponseEntity<ApiResponse<JsonNode>> getDashboardSnapshot(@PathVariable String view) {
        return dashboardReadService.getSnapshot(view)
                .map(payload -> ResponseEntity.ok(ApiResponse.of(payload)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the live session insight blob from Redis while the session is still active
     * ({@code session:insight:{insuredId}:{sessionId}}).
     */
    @GetMapping("/sessions/{insuredId}/{sessionId}/insight")
    public ResponseEntity<ApiResponse<JsonNode>> getSessionInsight(
            @PathVariable String insuredId,
            @PathVariable String sessionId
    ) {
        return sessionInsightReadService.getInsight(insuredId, sessionId)
                .map(payload -> ResponseEntity.ok(ApiResponse.of(payload)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* Returns the latest live stats snapshot for the requested date or today by default. */
    @GetMapping("/stats/live")
    public ResponseEntity<ApiResponse<StatsResponseDto>> getLiveStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(statsService.getLiveStats(date)));
    }

    /* Returns trend stats, falling back to SQL when Redis does not contain the snapshot. */
    @GetMapping("/trends/forecast")
    public ResponseEntity<ApiResponse<StatsResponseDto>> getTrendStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(statsService.getTrendStats(date)));
    }

    /* Returns the currently active anomaly marker for the insured user, if any. */
    @GetMapping("/anomaly/active/{insuredId}")
    public ResponseEntity<ApiResponse<AnomalyAlertDto>> getActiveAnomaly(@PathVariable String insuredId) {
        return activeAnomalyService.getActiveAnomaly(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* Opens an SSE stream that continuously pushes live stats snapshots. */
    @GetMapping(path = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveStatsStreamService.subscribe();
    }

    /* Clamp the requested page size to a sensible range for the API. */
    private int normalizeSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}