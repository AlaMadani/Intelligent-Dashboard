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

/**
 * REST controller for LLM-generated explanations of alert events.
 * Provides endpoints to retrieve cached explanations, fetch raw evidence,
 * and trigger on-demand explanation generation via the LLM pipeline.
 */
@RestController
@RequestMapping("/api/v1/explanations")
@RequiredArgsConstructor
public class ExplanationController {

    /** Service for reading raw evidence data associated with alert events. */
    private final LlmEvidenceReadService evidenceReadService;

    /** Service for retrieving cached or generating new LLM explanations. */
    private final LlmExplanationService explanationService;

    /**
     * Retrieves the raw evidence data for a specific alert event.
     * Evidence includes contextual information used by the LLM to generate explanations.
     *
     * @param eventId the unique identifier of the alert event
     * @return raw evidence object wrapped in a standard API response
     */
    @GetMapping("/alerts/{eventId}/evidence")
    public ResponseEntity<ApiResponse<Object>> getEvidence(@PathVariable String eventId) {
        return ResponseEntity.ok(ApiResponse.of(evidenceReadService.requireEvidenceAsObject(eventId)));
    }

    /**
     * Retrieves a previously cached LLM explanation for the given alert event.
     * Returns 404 NOT_FOUND if no cached explanation exists for this event.
     *
     * @param eventId the unique identifier of the alert event
     * @return cached explanation data wrapped in a standard API response
     * @throws com.neo.dashboard.exception.ApiException if no cached explanation is found
     */
    @GetMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36LlmExplanationResponse>> getCachedExplanation(@PathVariable String eventId) {
        /* Attempt to retrieve the cached explanation; fail with 404 if absent */
        return explanationService.getCached(eventId)
                .map(response -> ResponseEntity.ok(ApiResponse.of(response)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXPLANATION_NOT_FOUND", "Cached explanation not found"));
    }

    /**
     * Generates a new LLM explanation for the given alert event on demand.
     * An optional request body can be provided to customize the explanation parameters.
     *
     * @param eventId the unique identifier of the alert event
     * @param request optional customization parameters for the explanation generation
     * @return the newly generated explanation wrapped in a standard API response
     */
    @PostMapping("/alerts/{eventId}")
    public ResponseEntity<ApiResponse<V36LlmExplanationResponse>> generateExplanation(
            @PathVariable String eventId,
            @RequestBody(required = false) V36LlmExplanationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(explanationService.generate(eventId, request)));
    }
}
