package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.dto.v36.V36User360Response;
import com.neo.dashboard.service.V36AlertService;
import com.neo.dashboard.service.V36User360Service;
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
 * REST controller for the User 360 view.
 * Provides a comprehensive, consolidated view of an insured user's profile,
 * activity history, associated alerts, and risk assessment data.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class User360Controller {

    /** Service providing the 360-degree user profile and aggregated user data. */
    private final V36User360Service user360Service;

    /** Service providing alert data scoped to a specific user. */
    private final V36AlertService alertService;

    /**
     * Retrieves the full 360-degree view for a specific insured user.
     * Includes personal profile, session history, risk assessments,
     * behavioral patterns, and churn indicators.
     *
     * @param insuredId the unique identifier of the insured user
     * @return user 360 data wrapped in a standard API response
     */
    @GetMapping("/{insuredId}/360")
    public ResponseEntity<ApiResponse<V36User360Response>> getUser360(@PathVariable String insuredId) {
        return ResponseEntity.ok(ApiResponse.of(user360Service.getUser360(insuredId)));
    }

    /**
     * Retrieves a paginated, filterable list of alerts associated with a specific insured user.
     * Supports optional filtering by risk level and a time window.
     *
     * @param insuredId the unique identifier of the insured user
     * @param riskLevel optional severity filter for alerts (e.g. HIGH, MEDIUM, LOW)
     * @param from      optional start of the time window (inclusive)
     * @param to        optional end of the time window (inclusive)
     * @param limit     maximum number of alerts to return (default 100)
     * @param offset    number of records to skip for pagination (default 0)
     * @return paginated user alert summaries wrapped in a standard API response
     */
    @GetMapping("/{insuredId}/alerts")
    public ResponseEntity<ApiResponse<ApiPageResponse<V36LiveAlertSummaryDto>>> getUserAlerts(
            @PathVariable String insuredId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getUserAlerts(insuredId, riskLevel, from, to, limit, offset)));
    }
}
