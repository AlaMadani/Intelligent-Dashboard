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

@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityDashboardController {

    private final V36DashboardService dashboardService;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<V36SecurityOverviewResponse>> getOverview() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getSecurityOverview()));
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<V36DiagnosticsResponse>> getDiagnostics() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getDiagnostics()));
    }
}
