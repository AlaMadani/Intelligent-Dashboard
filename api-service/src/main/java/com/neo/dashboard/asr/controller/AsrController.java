package com.neo.dashboard.asr.controller;

import com.neo.dashboard.asr.dto.AsrStatus;
import com.neo.dashboard.asr.dto.AsrTranscriptionResult;
import com.neo.dashboard.asr.service.AsrService;
import com.neo.dashboard.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/asr")
@RequiredArgsConstructor
public class AsrController {

    private final AsrService asrService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<AsrStatus>> status() {
        return ResponseEntity.ok(ApiResponse.of(asrService.getStatus()));
    }

    @PostMapping("/transcribe")
    public ResponseEntity<ApiResponse<AsrTranscriptionResult>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "languageCode", required = false) String languageCode) {
        AsrTranscriptionResult result = asrService.transcribe(file, languageCode);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probe() {
        AsrStatus status = asrService.getStatus();
        Map<String, Object> probeResult = new java.util.LinkedHashMap<>();
        probeResult.put("provider", status.provider());
        probeResult.put("protocol", status.protocol());
        probeResult.put("method", status.method());
        probeResult.put("server", status.server());
        probeResult.put("model", status.model());
        probeResult.put("ffmpegAvailable", status.ffmpegAvailable());
        probeResult.put("apiKeyPresent", status.apiKeyPresent());
        probeResult.put("apiKeyLooksValid", status.apiKeyLooksValid());
        return ResponseEntity.ok(ApiResponse.of(probeResult));
    }
}
