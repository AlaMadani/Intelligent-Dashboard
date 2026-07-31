package com.neo.dashboard.asr.service;

import com.neo.dashboard.asr.dto.AsrStatus;
import com.neo.dashboard.asr.dto.AsrTranscriptionResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service interface for automatic speech recognition. Implementations wrap a
 * specific provider (e.g. NVIDIA Riva gRPC, NVIDIA NIM REST) and provide a
 * uniform contract for transcription and status reporting.
 */
public interface AsrService {
    /**
     * Transcribes the given audio file using the configured provider.
     *
     * @param file         the uploaded audio file
     * @param languageCode optional BCP-47 language override (null = use default)
     * @return transcription result with recognised text and metadata
     */
    AsrTranscriptionResult transcribe(MultipartFile file, String languageCode);

    /**
     * Returns a snapshot of the current ASR subsystem status, including
     * provider configuration, API key validity, and ffmpeg availability.
     */
    AsrStatus getStatus();
}
