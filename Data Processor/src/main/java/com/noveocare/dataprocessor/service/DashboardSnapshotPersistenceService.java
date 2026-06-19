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

@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardSnapshotPersistenceService {

    private final DashboardSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    private final AtomicLong sqlWriteSuccessTotal = new AtomicLong();
    private final AtomicLong sqlWriteFailureTotal = new AtomicLong();
    private final AtomicReference<Instant> sqlLastWriteAt = new AtomicReference<>();
    private final AtomicReference<Instant> sqlLastFailureAt = new AtomicReference<>();

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
                DashboardSnapshot snapshot = new DashboardSnapshot();
                snapshot.setSchemaVersion("v3.6.1");
                snapshot.setViewName(viewName);
                snapshot.setSnapshotKey(snapshotKey);
                snapshot.setSnapshotDate(today);
                snapshot.setSnapshotTimestamp(now);
                snapshot.setPayloadJson(payloadJson);
                snapshot.setPayloadHash(payloadHash);
                snapshot.setSource(source);
                snapshot.setCreatedAt(now);
                snapshot.setUpdatedAt(now);
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

    private void recordFailure(String viewName, String snapshotKey, Exception e) {
        sqlWriteFailureTotal.incrementAndGet();
        sqlLastFailureAt.set(Instant.now());
        log.warn("DASHBOARD_SNAPSHOT_PERSIST_FAILED view={} snapshotKey={} errorClass={} errorMessage={}",
                viewName, snapshotKey, e.getClass().getSimpleName(), e.getMessage());
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

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
