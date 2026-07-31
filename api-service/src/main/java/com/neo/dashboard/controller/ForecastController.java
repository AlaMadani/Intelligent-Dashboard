package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36ForecastDashboardResponse;
import com.neo.dashboard.service.V36DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for AI-powered forecast dashboards.
 * Provides forward-looking predictions and trend analysis
 * for key operational metrics in the V36 platform.
 */
@RestController
@RequestMapping("/api/v1/forecast")
@RequiredArgsConstructor
public class ForecastController {

    /** Service providing forecast dashboard data and predictive analytics. */
    private final V36DashboardService dashboardService;

    /**
     * Retrieves the forecast dashboard, including predicted trends,
     * projected values, and confidence intervals for key metrics.
     *
     * @return forecast dashboard data wrapped in a standard API response
     */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<V36ForecastDashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getForecastDashboard()));
    }
}
