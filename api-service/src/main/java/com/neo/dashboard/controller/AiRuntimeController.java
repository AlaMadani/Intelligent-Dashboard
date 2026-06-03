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

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiRuntimeController {

    private final V36DashboardService dashboardService;

    @GetMapping("/runtime-health")
    public ResponseEntity<ApiResponse<V36RuntimeHealthResponse>> getRuntimeHealth() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getRuntimeHealth()));
    }

    @GetMapping("/final-winners")
    public ResponseEntity<ApiResponse<V36FinalWinnersResponse>> getFinalWinners() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getFinalWinners()));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReports() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getReportMetadata()));
    }
}
