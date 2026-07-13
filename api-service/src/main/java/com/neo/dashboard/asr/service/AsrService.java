package com.neo.dashboard.asr.service;

import com.neo.dashboard.asr.dto.AsrStatus;
import com.neo.dashboard.asr.dto.AsrTranscriptionResult;
import org.springframework.web.multipart.MultipartFile;

public interface AsrService {
    AsrTranscriptionResult transcribe(MultipartFile file, String languageCode);
    AsrStatus getStatus();
}
