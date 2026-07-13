package com.neo.dashboard.asr.audio;

import com.neo.dashboard.asr.config.AsrProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioConverter {

    private static final String[] SUPPORTED_EXTENSIONS = {".wav", ".ogg", ".opus"};

    private final AsrProperties properties;
    private volatile boolean ffmpegAvailable = false;

    @jakarta.annotation.PostConstruct
    public void checkFfmpeg() {
        String path = properties.getAudio().getFfmpegPath();
        try {
            Process process = new ProcessBuilder(path, "-version")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            ffmpegAvailable = exit == 0;
            if (ffmpegAvailable) {
                log.info("ASR_FFMPEG_AVAILABLE ffmpegPath={}", path);
            } else {
                log.warn("ASR_FFMPEG_NOT_AVAILABLE ffmpegPath={} exitCode={}", path, exit);
            }
        } catch (Exception e) {
            ffmpegAvailable = false;
            log.warn("ASR_FFMPEG_NOT_AVAILABLE ffmpegPath={} error={}", path, e.getMessage());
        }
    }

    public boolean isFfmpegAvailable() {
        return ffmpegAvailable;
    }

    public boolean isSupportedFormat(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public byte[] convertToWav(byte[] input, String originalFilename, String requestId) {
        Path tempDir = null;
        Path inputFile = null;
        Path outputFile = null;
        try {
            tempDir = Files.createTempDirectory("asr-");
            String ext = "webm";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
            }
            inputFile = tempDir.resolve("input." + ext);
            outputFile = tempDir.resolve("output.wav");

            Files.write(inputFile, input);

            String ffmpegPath = properties.getAudio().getFfmpegPath();
            int sampleRate = properties.getAudio().getTargetSampleRate();
            int channels = properties.getAudio().getTargetChannels();

            log.info("ASR_AUDIO_CONVERSION_STARTED requestId={} sourceContentType={} targetFormat=wav targetSampleRate={} targetChannels={}",
                    requestId, ext, sampleRate, channels);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y", "-i", inputFile.toAbsolutePath().toString(),
                    "-ac", String.valueOf(channels),
                    "-ar", String.valueOf(sampleRate),
                    "-sample_fmt", "s16",
                    outputFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes());
                log.warn("ASR_AUDIO_CONVERSION_FAILED requestId={} exitCode={} error={}",
                        requestId, exitCode, errorOutput);
                return null;
            }

            byte[] result = Files.readAllBytes(outputFile);
            log.info("ASR_AUDIO_CONVERSION_DONE requestId={} convertedSizeBytes={}", requestId, result.length);
            return result;
        } catch (IOException | InterruptedException e) {
            log.warn("ASR_AUDIO_CONVERSION_FAILED requestId={} error={}", requestId, e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } finally {
            cleanup(tempDir, inputFile, outputFile);
        }
    }

    private void cleanup(Path tempDir, Path... files) {
        for (Path f : files) {
            if (f != null) try { Files.deleteIfExists(f); } catch (IOException ignored) {}
        }
        if (tempDir != null) try { Files.deleteIfExists(tempDir); } catch (IOException ignored) {}
    }
}
