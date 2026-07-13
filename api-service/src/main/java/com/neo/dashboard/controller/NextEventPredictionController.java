package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
import com.neo.dashboard.service.V36NextEventPredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NextEventPredictionController {

    private final V36NextEventPredictionService predictionService;

    @GetMapping("/users/{insuredId}/next-event-prediction")
    public ResponseEntity<ApiResponse<V36NextEventPredictionDto>> getUserPrediction(
            @PathVariable String insuredId) {
        return ResponseEntity.ok(ApiResponse.of(predictionService.getByInsuredId(insuredId)));
    }

    @GetMapping("/sessions/{sessionId}/next-event-prediction")
    public ResponseEntity<ApiResponse<V36NextEventPredictionDto>> getSessionPrediction(
            @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.of(predictionService.getBySessionId(sessionId)));
    }
}
