package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import com.neo.dashboard.dto.v36.V36LlmExplanationRequest;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.service.LlmEvidenceReadService;
import com.neo.dashboard.service.LlmExplanationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/explanations")
@RequiredArgsConstructor
public class ExplanationController {

    private final LlmEvidenceReadService evidenceReadService;
    private final LlmExplanationService explanationService;

    @GetMapping("/alerts/{eventId}/evidence")
    public ResponseEntity<ApiResponse<Object>> getEvidence(@PathVariable String eventId) {
        return ResponseEntity.ok(ApiResponse.of(evidenceReadService.requireEvidenceAsObject(eventId)));
    }

    @GetMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36LlmExplanationResponse>> getCachedExplanation(@PathVariable String eventId) {
        return explanationService.getCached(eventId)
                .map(response -> ResponseEntity.ok(ApiResponse.of(response)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXPLANATION_NOT_FOUND", "Cached explanation not found"));
    }

    @PostMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36LlmExplanationResponse>> generateExplanation(
            @PathVariable String eventId,
            @RequestBody(required = false) V36LlmExplanationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(explanationService.generate(eventId, request)));
    }
}
