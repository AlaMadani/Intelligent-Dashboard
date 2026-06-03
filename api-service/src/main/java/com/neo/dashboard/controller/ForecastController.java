package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36ForecastDashboardResponse;
import com.neo.dashboard.service.V36DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final V36DashboardService dashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<V36ForecastDashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getForecastDashboard()));
    }
}
