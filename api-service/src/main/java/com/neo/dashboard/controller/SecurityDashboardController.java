package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36DiagnosticsResponse;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.service.V36DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the security analytics dashboard.
 * Exposes high-level security overview metrics and detailed
 * diagnostic information about the V36 security monitoring system.
 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityDashboardController {

    /** Service providing security dashboard KPIs and diagnostic data. */
    private final V36DashboardService dashboardService;

    /**
     * Retrieves the security overview dashboard, including summary statistics
     * such as total threats detected, blocked attempts, active incidents,
     * and overall security posture ratings.
     *
     * @return security overview data wrapped in a standard API response
     */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<V36SecurityOverviewResponse>> getOverview() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getSecurityOverview()));
    }

    /**
     * Retrieves detailed diagnostics about the security monitoring system.
     * Includes component health, data pipeline status, and configuration
     * validation results for troubleshooting and audit purposes.
     *
     * @return diagnostics data wrapped in a standard API response
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<V36DiagnosticsResponse>> getDiagnostics() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getDiagnostics()));
    }
}
