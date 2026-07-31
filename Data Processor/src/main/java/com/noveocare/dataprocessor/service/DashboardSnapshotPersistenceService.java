package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.entity.DashboardSnapshot;
import com.noveocare.dataprocessor.repository.DashboardSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists dashboard snapshots to PostgreSQL with SHA-256 deduplication.
 * Skips writes when the payload hash matches the latest stored snapshot.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardSnapshotPersistenceService {

    /* Injected dependencies */
    private final DashboardSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    /* SQL write metrics */
    private final AtomicLong sqlWriteSuccessTotal = new AtomicLong();
    private final AtomicLong sqlWriteFailureTotal = new AtomicLong();
    private final AtomicReference<Instant> sqlLastWriteAt = new AtomicReference<>();
    private final AtomicReference<Instant> sqlLastFailureAt = new AtomicReference<>();

    /* --- Persistence logic --- */

    @Transactional
    public void persistSnapshot(String viewName, String snapshotKey, Object payload, String source) {
        long startMs = System.currentTimeMillis();
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadHash = sha256Hex(payloadJson);
            Instant now = Instant.now();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            DashboardSnapshot existing = repository
                    .findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey)
                    .orElse(null);

            if (existing != null) {
                if (payloadHash.equals(existing.getPayloadHash())) {
                    long sqlMs = System.currentTimeMillis() - startMs;
                    log.debug("DASHBOARD_SNAPSHOT_PERSIST view={} snapshotKey={} payloadBytes={} payloadHash={} status=SKIPPED_UNCHANGED sqlMs={}",
                            viewName, snapshotKey, payloadJson.length(), payloadHash, sqlMs);
                    return;
                }
                existing.setPayloadJson(payloadJson);
                existing.setPayloadHash(payloadHash);
                existing.setSnapshotTimestamp(now);
                existing.setSnapshotDate(today);
                existing.setSource(source);
                existing.setUpdatedAt(now);
                repository.save(existing);
            } else {
                DashboardSnapshot snapshot = DashboardSnapshot.builder()
                        .schemaVersion("v3.6.1")
                        .viewName(viewName)
                        .snapshotKey(snapshotKey)
                        .snapshotDate(today)
                        .snapshotTimestamp(now)
                        .payloadJson(payloadJson)
                        .payloadHash(payloadHash)
                        .source(source)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                repository.save(snapshot);
            }

            sqlWriteSuccessTotal.incrementAndGet();
            sqlLastWriteAt.set(now);
            long sqlMs = System.currentTimeMillis() - startMs;
            log.info("DASHBOARD_SNAPSHOT_PERSIST view={} snapshotKey={} redisWriteMs=N/A sqlWriteMs={} payloadBytes={} payloadHash={} status=OK",
                    viewName, snapshotKey, sqlMs, payloadJson.length(), payloadHash);
        } catch (JsonProcessingException e) {
            recordFailure(viewName, snapshotKey, e);
        } catch (Exception e) {
            recordFailure(viewName, snapshotKey, e);
        }
    }

    /* Records a write failure and updates metrics */
    private void recordFailure(String viewName, String snapshotKey, Exception e) {
        sqlWriteFailureTotal.incrementAndGet();
        sqlLastFailureAt.set(Instant.now());
        log.warn("DASHBOARD_SNAPSHOT_PERSIST_FAILED view={} snapshotKey={} errorClass={} errorMessage={}",
                viewName, snapshotKey, e.getClass().getSimpleName(), e.getMessage());
    }

    /* Computes SHA-256 hex digest of the input string */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /* --- Diagnostics --- */

    public long getSqlWriteSuccessTotal() {
        return sqlWriteSuccessTotal.get();
    }

    public long getSqlWriteFailureTotal() {
        return sqlWriteFailureTotal.get();
    }

    public Instant getSqlLastWriteAt() {
        return sqlLastWriteAt.get();
    }

    public Instant getSqlLastFailureAt() {
        return sqlLastFailureAt.get();
    }
}
