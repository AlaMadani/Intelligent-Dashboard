package com.neo.dashboard.assistant;

import com.neo.dashboard.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Dashboard Assistant feature, exposed under
 * {@code /api/v1/dashboard-assistant}. Provides endpoints for sending user
 * messages, retrieving capabilities, running A/B benchmarks across models,
 * and probing NVIDIA NIM endpoints.
 */
@RestController
@RequestMapping("/api/v1/dashboard-assistant")
@RequiredArgsConstructor
public class DashboardAssistantController {

    /** Core service that processes user messages and returns assistant responses. */
    private final DashboardAssistantService assistantService;
    /** Registry holding the loaded capability manifest for validation and lookups. */
    private final DashboardAssistantCapabilityRegistry registry;

    /**
     * Accepts a user message along with current route context and visible
     * elements, processes it through the assistant pipeline, and returns a
     * structured response with optional commands.
     */
    @PostMapping("/message")
    public ResponseEntity<ApiResponse<DashboardAssistantResponse>> handleMessage(
            @RequestBody DashboardAssistantRequest request) {
        DashboardAssistantResponse response = assistantService.handleMessage(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Returns the full capability manifest (routes, elements, tasks, panels,
     * filters, refresh/search targets) with computed item counts, for the
     * frontend to render and for debugging.
     */
    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCapabilities() {
        Map<String, Object> manifest = registry.buildCapabilitiesManifest();
        // Append a counts map so the frontend can show capability sizes at a glance
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("routes", manifest.get("routes") instanceof List ? ((List<?>) manifest.get("routes")).size() : 0);
        counts.put("elements", manifest.get("elements") instanceof List ? ((List<?>) manifest.get("elements")).size() : 0);
        counts.put("tasks", manifest.get("tasks") instanceof List ? ((List<?>) manifest.get("tasks")).size() : 0);
        counts.put("filters", manifest.get("filters") instanceof List ? ((List<?>) manifest.get("filters")).size() : 0);
        counts.put("refreshTargets", manifest.get("refreshTargets") instanceof List ? ((List<?>) manifest.get("refreshTargets")).size() : 0);
        manifest.put("counts", counts);
        return ResponseEntity.ok(ApiResponse.of(manifest));
    }

    /**
     * Runs an A/B benchmark across one or more candidate models using the
     * given prompt (or the first prompt from the "prompts" array). Returns
     * per-model latency, parse success, and a summary with recommendations.
     */
    @PostMapping("/benchmark")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> runBenchmark(
            @RequestBody Map<String, Object> request) {
        // Extract the prompt from the request body (supports both "prompt" and "prompts" array)
        String prompt = "List what you can do.";
        if (request.containsKey("prompt") && request.get("prompt") instanceof String) {
            prompt = (String) request.get("prompt");
        } else if (request.containsKey("prompts") && request.get("prompts") instanceof List) {
            List<?> prompts = (List<?>) request.get("prompts");
            if (!prompts.isEmpty() && prompts.get(0) instanceof String) {
                prompt = (String) prompts.get(0);
            }
        }
        // Extract an explicit list of models, or default to the candidate list
        List<String> models = null;
        if (request.containsKey("models") && request.get("models") instanceof List) {
            List<?> rawModels = (List<?>) request.get("models");
            models = rawModels.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        List<Map<String, Object>> results = assistantService.runBenchmark(prompt, models);
        return ResponseEntity.ok(ApiResponse.of(results));
    }

    /**
     * Probes one or more NVIDIA NIM models with a given message to test
     * connectivity, latency, guided JSON support, and parseability. Supports
     * both a single "model" field (backward compat) and a "models" array.
     */
    @PostMapping("/nim-probe")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nimProbe(
            @RequestBody Map<String, Object> request) {
        String message = (String) request.getOrDefault("message",
                "Return JSON: {\"message\":\"ok\",\"commands\":[],\"requiresConfirmation\":false}");
        boolean useGuidedJson = request.containsKey("useGuidedJson")
                ? Boolean.TRUE.equals(request.get("useGuidedJson"))
                : false;
        boolean dumpRequestShape = request.containsKey("dumpRequestShape")
                ? Boolean.TRUE.equals(request.get("dumpRequestShape"))
                : false;

        // Single model (backward compat)
        String model = (String) request.get("model");
        if (model != null && !model.isBlank()) {
            Map<String, Object> result = assistantService.nimProbe(message, model, useGuidedJson, dumpRequestShape);
            return ResponseEntity.ok(ApiResponse.of(List.of(result)));
        }

        // Multi-model array — probe each candidate model in sequence
        @SuppressWarnings("unchecked")
        List<String> models = request.containsKey("models")
                ? ((List<Object>) request.get("models")).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList()
                : assistantService.getCandidateModelsPublic();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String candidateModel : models) {
            results.add(assistantService.nimProbe(message, candidateModel, useGuidedJson, dumpRequestShape));
        }
        return ResponseEntity.ok(ApiResponse.of(results));
    }
}
