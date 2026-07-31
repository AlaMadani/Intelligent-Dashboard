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

/**
 * REST controller for next-event prediction in the V36 platform.
 * Provides predictions about the most likely future event for a given
 * insured user or active session, based on historical patterns and ML models.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NextEventPredictionController {

    /** Service that computes next-event predictions from historical and real-time data. */
    private final V36NextEventPredictionService predictionService;

    /**
     * Retrieves the next-event prediction for a specific insured user.
     * The prediction identifies the most probable future event type and its
     * estimated timing based on the user's historical pattern.
     *
     * @param insuredId the unique identifier of the insured user
     * @return next-event prediction data wrapped in a standard API response
     */
    @GetMapping("/users/{insuredId}/next-event-prediction")
    public ResponseEntity<ApiResponse<V36NextEventPredictionDto>> getUserPrediction(
            @PathVariable String insuredId) {
        return ResponseEntity.ok(ApiResponse.of(predictionService.getByInsuredId(insuredId)));
    }

    /**
     * Retrieves the next-event prediction for a specific session.
     * The prediction considers the session's current context and behavioral
     * patterns to forecast the next likely event.
     *
     * @param sessionId the unique identifier of the session
     * @return next-event prediction data wrapped in a standard API response
     */
    @GetMapping("/sessions/{sessionId}/next-event-prediction")
    public ResponseEntity<ApiResponse<V36NextEventPredictionDto>> getSessionPrediction(
            @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.of(predictionService.getBySessionId(sessionId)));
    }
}
