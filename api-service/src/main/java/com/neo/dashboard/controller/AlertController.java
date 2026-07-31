package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.service.V36AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST controller for alert management in the V36 security analytics platform.
 * Provides paginated access to live and critical alerts, as well as detailed
 * drill-down investigation data for a specific alert event.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    /** Service handling alert retrieval, filtering, and pagination logic. */
    private final V36AlertService alertService;

    /**
     * Retrieves a paginated, filterable list of live alerts currently being tracked.
     * Supports optional filtering by risk level, anomaly type, insured identity,
     * session identifier, and a time window (from/to).
     *
     * @param riskLevel   optional severity filter (e.g. HIGH, MEDIUM, LOW)
     * @param anomalyType optional anomaly classification filter
     * @param insuredId   optional insured-party identifier filter
     * @param sessionId   optional session identifier filter
     * @param from        optional start of the time window (inclusive)
     * @param to          optional end of the time window (inclusive)
     * @param limit       maximum number of records to return (default 100)
     * @param offset      number of records to skip for pagination (default 0)
     * @return paginated live alert summaries wrapped in a standard API response
     */
    @GetMapping("/alerts/live")
    public ResponseEntity<ApiResponse<ApiPageResponse<V36LiveAlertSummaryDto>>> getLiveAlerts(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        /* Delegate to service with all optional filter parameters */
        return ResponseEntity.ok(ApiResponse.of(alertService.getLiveAlerts(
                riskLevel, anomalyType, insuredId, sessionId, from, to, limit, offset)));
    }

    /**
     * Retrieves a paginated list of alerts classified as critical.
     * Critical alerts represent the highest-priority events requiring immediate attention.
     *
     * @param limit  maximum number of critical alerts to return (default 100)
     * @param offset number of records to skip for pagination (default 0)
     * @return paginated critical alert summaries wrapped in a standard API response
     */
    @GetMapping("/alerts/critical")
    public ResponseEntity<ApiResponse<ApiPageResponse<V36LiveAlertSummaryDto>>> getCriticalAlerts(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getCriticalAlerts(limit, offset)));
    }

    /**
     * Retrieves detailed investigation data for a specific alert event.
     * Includes related evidence, timeline information, and contextual metadata
     * needed for in-depth analysis of the alert.
     *
     * @param eventId the unique identifier of the alert event
     * @return detailed alert investigation data wrapped in a standard API response
     */
    @GetMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36AlertInvestigationDetailDto>> getAlertDetail(@PathVariable String eventId) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getAlertDetail(eventId)));
    }
}
