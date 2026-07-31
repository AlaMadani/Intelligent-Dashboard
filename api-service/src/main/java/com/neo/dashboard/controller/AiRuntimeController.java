package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36FinalWinnersResponse;
import com.neo.dashboard.dto.v36.V36RuntimeHealthResponse;
import com.neo.dashboard.service.V36DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing AI runtime monitoring and reporting endpoints.
 * Provides dashboards for runtime health status, finalized winner computations,
 * and aggregated report metadata from the V36 AI pipeline.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiRuntimeController {

    /** Service that aggregates and serves V36 AI dashboard data. */
    private final V36DashboardService dashboardService;

    /**
     * Retrieves the current health status of the AI runtime environment,
     * including component availability and performance metrics.
     *
     * @return runtime health summary wrapped in a standard API response
     */
    @GetMapping("/runtime-health")
    public ResponseEntity<ApiResponse<V36RuntimeHealthResponse>> getRuntimeHealth() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getRuntimeHealth()));
    }

    /**
     * Retrieves the list of final winners determined by the AI pipeline.
     * Winners represent the top-ranked predictions or classifications
     * after all processing stages have completed.
     *
     * @return final winners data wrapped in a standard API response
     */
    @GetMapping("/final-winners")
    public ResponseEntity<ApiResponse<V36FinalWinnersResponse>> getFinalWinners() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getFinalWinners()));
    }

    /**
     * Retrieves metadata about available AI-generated reports.
     * Provides summary information such as report names, timestamps,
     * and generation status for downstream navigation.
     *
     * @return report metadata map wrapped in a standard API response
     */
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReports() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getReportMetadata()));
    }
}
