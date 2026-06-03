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

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class User360Controller {

    private final V36User360Service user360Service;
    private final V36AlertService alertService;

    @GetMapping("/{insuredId}/360")
    public ResponseEntity<ApiResponse<V36User360Response>> getUser360(@PathVariable String insuredId) {
        return ResponseEntity.ok(ApiResponse.of(user360Service.getUser360(insuredId)));
    }

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
