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

/**
 * REST controller exposing ASR (Automatic Speech Recognition) endpoints under
 * {@code /api/v1/asr}. Provides operations to check provider status, transcribe
 * uploaded audio files, and probe configuration details for debugging.
 */
@RestController
@RequestMapping("/api/v1/asr")
@RequiredArgsConstructor
public class AsrController {

    /** Delegate service that implements the actual ASR transcription logic. */
    private final AsrService asrService;

    /**
     * Returns the current ASR provider status including whether it is enabled,
     * which provider/protocol/model is configured, API key validity, and
     * ffmpeg availability.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<AsrStatus>> status() {
        return ResponseEntity.ok(ApiResponse.of(asrService.getStatus()));
    }

    /**
     * Accepts a multipart audio file upload and transcribes it using the
     * configured ASR provider. An optional language code can override the
     * default configured language.
     *
     * @param file         the audio file to transcribe
     * @param languageCode optional BCP-47 language override
     * @return transcription result containing text, provider info, latency
     */
    @PostMapping("/transcribe")
    public ResponseEntity<ApiResponse<AsrTranscriptionResult>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "languageCode", required = false) String languageCode) {
        AsrTranscriptionResult result = asrService.transcribe(file, languageCode);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * Probe endpoint that returns a flat {@link Map} of key configuration values
     * for quick debugging from a browser or CLI without needing to parse the
     * full status object.
     */
    @GetMapping("/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probe() {
        AsrStatus status = asrService.getStatus();
        // Assemble a flat map of the most useful diagnostic fields
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
