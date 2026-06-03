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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    private final V36AlertService alertService;

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
        return ResponseEntity.ok(ApiResponse.of(alertService.getLiveAlerts(
                riskLevel, anomalyType, insuredId, sessionId, from, to, limit, offset)));
    }

    @GetMapping("/alerts/critical")
    public ResponseEntity<ApiResponse<ApiPageResponse<V36LiveAlertSummaryDto>>> getCriticalAlerts(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getCriticalAlerts(limit, offset)));
    }

    @GetMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36AlertInvestigationDetailDto>> getAlertDetail(@PathVariable String eventId) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getAlertDetail(eventId)));
    }
}
