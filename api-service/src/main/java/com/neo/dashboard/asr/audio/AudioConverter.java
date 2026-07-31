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

    /** File extensions that ffmpeg can handle natively without re-encoding. */
    private static final String[] SUPPORTED_EXTENSIONS = {".wav", ".ogg", ".opus"};

    /** Application configuration properties for ASR audio settings. */
    private final AsrProperties properties;
    /** Cached flag indicating whether the ffmpeg binary is available on the system. */
    private volatile boolean ffmpegAvailable = false;

    /**
     * Verifies that the ffmpeg binary configured in properties is reachable and
     * responds correctly. Sets the internal {@code ffmpegAvailable} flag based on
     * the exit code of "ffmpeg -version". This runs after dependency injection.
     */
    @jakarta.annotation.PostConstruct
    public void checkFfmpeg() {
        String path = properties.getAudio().getFfmpegPath();
        try {
            // Run ffmpeg -version to probe availability
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

    /** Returns whether ffmpeg was found and validated during startup. */
    public boolean isFfmpegAvailable() {
        return ffmpegAvailable;
    }

    /**
     * Checks whether the given filename has an extension that ffmpeg can handle
     * as-is (WAV, OGG, OPUS). Returns false for null filenames or unsupported types.
     */
    public boolean isSupportedFormat(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Converts an arbitrary audio byte array to a 16-bit mono WAV at the configured
     * sample rate using ffmpeg. Writes the input to a temp file, runs the conversion,
     * reads back the result, and cleans up temp files. Returns null on any failure.
     *
     * @param input            raw bytes of the source audio
     * @param originalFilename original name used to guess the source container format
     * @param requestId        correlation identifier for request-scoped logging
     * @return WAV byte array, or null if conversion failed
     */
    public byte[] convertToWav(byte[] input, String originalFilename, String requestId) {
        Path tempDir = null;
        Path inputFile = null;
        Path outputFile = null;
        try {
            // Create a temporary directory to hold the intermediate files
            tempDir = Files.createTempDirectory("asr-");
            // Infer the source extension from the original filename, defaulting to "webm"
            String ext = "webm";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
            }
            inputFile = tempDir.resolve("input." + ext);
            outputFile = tempDir.resolve("output.wav");

            // Write the incoming audio bytes to disk for ffmpeg to read
            Files.write(inputFile, input);

            String ffmpegPath = properties.getAudio().getFfmpegPath();
            int sampleRate = properties.getAudio().getTargetSampleRate();
            int channels = properties.getAudio().getTargetChannels();

            log.info("ASR_AUDIO_CONVERSION_STARTED requestId={} sourceContentType={} targetFormat=wav targetSampleRate={} targetChannels={}",
                    requestId, ext, sampleRate, channels);

            // Build the ffmpeg command: re-sample, re-channel, force 16-bit PCM
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

            // If ffmpeg returned non-zero, capture its stderr for diagnostics
            if (exitCode != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes());
                log.warn("ASR_AUDIO_CONVERSION_FAILED requestId={} exitCode={} error={}",
                        requestId, exitCode, errorOutput);
                return null;
            }

            // Read the converted WAV file from disk
            byte[] result = Files.readAllBytes(outputFile);
            log.info("ASR_AUDIO_CONVERSION_DONE requestId={} convertedSizeBytes={}", requestId, result.length);
            return result;
        } catch (IOException | InterruptedException e) {
            log.warn("ASR_AUDIO_CONVERSION_FAILED requestId={} error={}", requestId, e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // Ensure all temp files and the temp directory are removed
            cleanup(tempDir, inputFile, outputFile);
        }
    }

    /** Deletes each temp file and the parent temp directory, silently ignoring errors. */
    private void cleanup(Path tempDir, Path... files) {
        for (Path f : files) {
            if (f != null) try { Files.deleteIfExists(f); } catch (IOException ignored) {}
        }
        if (tempDir != null) try { Files.deleteIfExists(tempDir); } catch (IOException ignored) {}
    }
}
