package com.neo.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.*;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.mapper.UserRiskProfileMapper;
import com.neo.dashboard.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final SessionAnalysisService sessionAnalysisService;
    private final AnomalyEventService anomalyEventService;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final StatsService statsService;
    private final StatsSummaryService statsSummaryService;
    private final CommandCenterService commandCenterService;
    private final ActiveSessionService activeSessionService;
    private final AnomalyInvestigationService anomalyInvestigationService;
    private final ActiveAnomalyService activeAnomalyService;
    private final AnomalyExplanationService anomalyExplanationService;
    private final LiveStatsStreamService liveStatsStreamService;
    private final DashboardReadService dashboardReadService;
    private final SessionInsightReadService sessionInsightReadService;
    private final UserDashboardService userDashboardService;
    private final UserRiskProfileRepositoryHelper userRiskProfileRepositoryHelper;
    private final SessionAnalysisMapper sessionAnalysisMapper;
    private final AnomalyEventMapper anomalyEventMapper;
    private final UserRiskProfileMapper userRiskProfileMapper;
    private final ObjectMapper objectMapper;

    // ───── Session endpoints ─────

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionAnalysisDto>>> listSessions(
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Boolean isAnomaly,
            @RequestParam(required = false) String signature,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "startTime"));
        Page<SessionAnalysisDto> result = sessionAnalysisService
                .search(insuredId, from, to, isAnomaly, signature, pageable)
                .map(sessionAnalysisMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<SessionAnalysisDto>> getSession(@PathVariable Long id) {
        return sessionAnalysisService.getById(id)
                .map(sessionAnalysisMapper::toDto)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/active")
    public ResponseEntity<ApiResponse<List<ActiveSessionDto>>> getActiveSessions(
            @RequestParam(required = false) String insuredId,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                activeSessionService.getActiveSessions(insuredId, anomalyOnly, limit)));
    }

    @GetMapping("/sessions/{insuredId}/{sessionId}/insight")
    public ResponseEntity<ApiResponse<Object>> getSessionInsight(
            @PathVariable String insuredId,
            @PathVariable String sessionId
    ) {
        return sessionInsightReadService.getInsight(insuredId, sessionId)
                .map(payload -> ResponseEntity.ok(ApiResponse.of(objectMapper.convertValue(payload, Object.class))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ───── User-specific endpoints ─────

    @GetMapping("/users/{insuredId}/sessions")
    public ResponseEntity<ApiResponse<List<SessionAnalysisDto>>> getUserSessions(
            @PathVariable String insuredId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(normalizeSize(size), 100),
                Sort.by(Sort.Direction.DESC, "startTime"));
        Page<SessionAnalysisDto> result = sessionAnalysisService
                .search(insuredId, null, null, null, null, pageable)
                .map(sessionAnalysisMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    @GetMapping("/users/{insuredId}/dashboard")
    public ResponseEntity<ApiResponse<UserDashboardDto>> getUserDashboard(@PathVariable String insuredId) {
        return userDashboardService.getDashboard(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ───── Risk profile endpoints ─────

    @GetMapping("/risk-profiles/{insuredId}")
    public ResponseEntity<ApiResponse<UserRiskProfileDto>> getRiskProfile(@PathVariable String insuredId) {
        return riskProfileService.getRiskProfile(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/risk-profiles")
    public ResponseEntity<ApiResponse<List<UserRiskProfileDto>>> listRiskProfiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<UserRiskProfileDto> result = userRiskProfileRepositoryHelper
                .findAll(pageable)
                .map(userRiskProfileMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    // ───── Anomaly endpoints ─────

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
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "eventTime"));
        Page<AnomalyEventDto> result = anomalyEventService
                .search(insuredId, from, to, tier, type, pageable)
                .map(anomalyEventMapper::toDto);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), PaginationMeta.fromPage(result)));
    }

    @GetMapping("/anomalies/{id}")
    public ResponseEntity<ApiResponse<AnomalyEventDto>> getAnomalyEvent(@PathVariable Long id) {
        return anomalyEventService.getById(id)
                .map(anomalyEventMapper::toDto)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/anomalies/{id}/investigation")
    public ResponseEntity<ApiResponse<AnomalyInvestigationDto>> getAnomalyInvestigation(@PathVariable Long id) {
        return anomalyInvestigationService.getInvestigation(id)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/anomalies/{id}/explain")
    public ResponseEntity<ApiResponse<AnomalyExplanationDto>> explainAnomalyEvent(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return anomalyExplanationService.explainAsync(id, refresh)
                .join()
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/anomalies/active/{insuredId}")
    public ResponseEntity<ApiResponse<AnomalyAlertDto>> getActiveAnomaly(@PathVariable String insuredId) {
        return activeAnomalyService.getActiveAnomaly(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ───── Next actions ─────

    @GetMapping("/next-actions/{insuredId}")
    public ResponseEntity<ApiResponse<NextActionPredictionDto>> getNextActions(@PathVariable String insuredId) {
        return nextActionPredictionService.getPrediction(insuredId)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ───── Stats endpoints ─────

    @GetMapping("/stats/live")
    public ResponseEntity<ApiResponse<StatsApiResponseDto>> getLiveStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(toApiDto(statsService.getLiveStats(date))));
    }

    @GetMapping("/stats/summary")
    public ResponseEntity<ApiResponse<StatsSummaryDto>> getStatsSummary() {
        return ResponseEntity.ok(ApiResponse.of(statsSummaryService.getSummary()));
    }

    @GetMapping("/trends/forecast")
    public ResponseEntity<ApiResponse<StatsApiResponseDto>> getTrendStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(ApiResponse.of(toApiDto(statsService.getTrendStats(date))));
    }

    // ───── Dashboard endpoints ─────

    @GetMapping("/dashboard/command-center")
    public ResponseEntity<ApiResponse<Object>> getCommandCenter(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        CommandCenterDto dto = commandCenterService.getCommandCenter(date);
        return ResponseEntity.ok(ApiResponse.of(objectMapper.convertValue(dto, Map.class)));
    }

    @GetMapping("/dashboard/{view}")
    public ResponseEntity<ApiResponse<Object>> getDashboardSnapshot(@PathVariable String view) {
        return dashboardReadService.getSnapshotOrDefault(view)
                .map(payload -> ResponseEntity.ok(ApiResponse.of(objectMapper.convertValue(payload, Object.class))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ───── Health ─────

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHealth() {
        boolean redisOk = true;
        boolean dbOk = true;
        try {
            activeSessionService.countActive();
        } catch (Exception e) {
            redisOk = false;
        }
        try {
            sessionAnalysisService.getById(1L);
        } catch (Exception e) {
            dbOk = false;
        }
        String status = (redisOk && dbOk) ? "UP" : "DEGRADED";
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "status", status,
                "checks", Map.of("redis", redisOk ? "UP" : "DOWN", "database", dbOk ? "UP" : "DOWN"),
                "timestamp", Instant.now().toString()
        )));
    }

    // ───── SSE stream ─────

    @GetMapping(path = "/stream/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveStatsStreamService.subscribe();
    }

    // ───── Helpers ─────

    private StatsApiResponseDto toApiDto(StatsResponseDto stats) {
        if (stats == null) return null;
        Object payload = stats.getPayload() == null ? null
                : objectMapper.convertValue(stats.getPayload(), Object.class);
        return new StatsApiResponseDto(stats.getDate(), stats.getSource(), payload);
    }

    private int normalizeSize(int size) {
        if (size < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}