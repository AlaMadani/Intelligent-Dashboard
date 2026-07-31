package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36ChurnDashboardResponse;
import com.neo.dashboard.service.V36DashboardService;
import com.neo.dashboard.service.V36User360Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for churn analysis and prediction dashboards.
 * Provides high-level churn metrics and a paginated list of users
 * flagged with churn risk indicators.
 */
@RestController
@RequestMapping("/api/v1/churn")
@RequiredArgsConstructor
public class ChurnController {

    /** Service providing aggregated churn dashboard metrics and KPIs. */
    private final V36DashboardService dashboardService;

    /** Service providing user-level 360-degree data including churn indicators. */
    private final V36User360Service user360Service;

    /**
     * Retrieves the churn dashboard summary, including overall churn rate,
     * risk distribution, trend data, and key performance indicators.
     *
     * @return churn dashboard data wrapped in a standard API response
     */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<V36ChurnDashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getChurnDashboard()));
    }

    /**
     * Retrieves a paginated list of users identified as churn risks.
     * Results can be optionally filtered by risk level.
     *
     * @param riskLevel optional churn risk level filter (e.g. HIGH, MEDIUM, LOW)
     * @param limit     maximum number of users to return (default 50)
     * @return paginated list of churn-risk users wrapped in a standard API response
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<ApiPageResponse<Map<String, Object>>>> getChurnUsers(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.of(user360Service.getChurnUsers(riskLevel, limit)));
    }
}
