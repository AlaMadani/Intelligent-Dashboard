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

@RestController
@RequestMapping("/api/v1/churn")
@RequiredArgsConstructor
public class ChurnController {

    private final V36DashboardService dashboardService;
    private final V36User360Service user360Service;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<V36ChurnDashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getChurnDashboard()));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<ApiPageResponse<Map<String, Object>>>> getChurnUsers(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.of(user360Service.getChurnUsers(riskLevel, limit)));
    }
}
