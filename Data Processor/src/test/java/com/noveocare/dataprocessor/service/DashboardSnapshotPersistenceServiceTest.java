package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.entity.DashboardSnapshot;
import com.noveocare.dataprocessor.repository.DashboardSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for DashboardSnapshotPersistenceService: insert, update, deduplication
 * by hash, exception handling, and success/failure counters.
 */
class DashboardSnapshotPersistenceServiceTest {

    /* --- Fields --- */

    private final DashboardSnapshotRepository repository = mock(DashboardSnapshotRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DashboardSnapshotPersistenceService service;

    /* --- Setup --- */

    @BeforeEach
    void setUp() {
        service = new DashboardSnapshotPersistenceService(repository, objectMapper);
    }

    /* --- Test methods: insert --- */

    @Test
    void persistSnapshotInsertsNewRow() {
        String viewName = "security-overview";
        String snapshotKey = "security-overview:latest";
        Map<String, Object> payload = Map.of(
                "schemaVersion", "v3.6.1",
                "totalEventsToday", 100L,
                "activeUsersToday", 10L);

        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey))
                .thenReturn(Optional.empty());

        service.persistSnapshot(viewName, snapshotKey, payload, "dashboard_refresh");

        ArgumentCaptor<DashboardSnapshot> captor = ArgumentCaptor.forClass(DashboardSnapshot.class);
        verify(repository, times(1)).save(captor.capture());
        DashboardSnapshot saved = captor.getValue();

        assertThat(saved.getViewName()).isEqualTo(viewName);
        assertThat(saved.getSnapshotKey()).isEqualTo(snapshotKey);
        assertThat(saved.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(saved.getSource()).isEqualTo("dashboard_refresh");
        assertThat(saved.getPayloadHash()).isNotBlank();
        assertThat(saved.getPayloadJson()).contains("totalEventsToday");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getSnapshotTimestamp()).isNotNull();
        assertThat(saved.getSnapshotDate()).isNotNull();
    }

    /* --- Test methods: update --- */

    @Test
    void persistSnapshotUpdatesExistingRow() {
        String viewName = "security-overview";
        String snapshotKey = "security-overview:latest";
        Map<String, Object> payload = Map.of(
                "schemaVersion", "v3.6.1",
                "totalEventsToday", 200L);

        DashboardSnapshot existing = new DashboardSnapshot();
        existing.setId(1L);
        existing.setViewName(viewName);
        existing.setSnapshotKey(snapshotKey);
        existing.setPayloadJson("{\"old\":\"data\"}");
        existing.setPayloadHash("old-hash");
        existing.setCreatedAt(Instant.now().minusSeconds(3600));
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));
        existing.setSnapshotTimestamp(Instant.now().minusSeconds(3600));

        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey))
                .thenReturn(Optional.of(existing));

        service.persistSnapshot(viewName, snapshotKey, payload, "dashboard_refresh");

        ArgumentCaptor<DashboardSnapshot> captor = ArgumentCaptor.forClass(DashboardSnapshot.class);
        verify(repository, times(1)).save(captor.capture());
        DashboardSnapshot updated = captor.getValue();

        assertThat(updated.getId()).isEqualTo(1L);
        assertThat(updated.getPayloadJson()).contains("totalEventsToday");
        assertThat(updated.getUpdatedAt()).isAfter(existing.getCreatedAt());
    }

    /* --- Test methods: deduplication --- */

    @Test
    void persistSnapshotSkipsWhenHashUnchanged() {
        String viewName = "security-overview";
        String snapshotKey = "security-overview:latest";
        Map<String, Object> payload = Map.of(
                "schemaVersion", "v3.6.1",
                "totalEventsToday", 100L);

        // First insert to compute hash
        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey))
                .thenReturn(Optional.empty());
        service.persistSnapshot(viewName, snapshotKey, payload, "dashboard_refresh");
        verify(repository, times(1)).save(any());

        // Reset and simulate existing with same hash
        reset(repository);
        String sameHash;
        try {
            String json = objectMapper.writeValueAsString(payload);
            sameHash = sha256Hex(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        DashboardSnapshot existing = new DashboardSnapshot();
        existing.setId(1L);
        existing.setViewName(viewName);
        existing.setSnapshotKey(snapshotKey);
        existing.setPayloadHash(sameHash);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));
        existing.setSnapshotTimestamp(Instant.now().minusSeconds(3600));

        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey))
                .thenReturn(Optional.of(existing));

        service.persistSnapshot(viewName, snapshotKey, payload, "dashboard_refresh");

        verify(repository, never()).save(any());
    }

    /* --- Test methods: error handling --- */

    @Test
    void persistSnapshotDoesNotFailOnException() {
        String viewName = "security-overview";
        String snapshotKey = "security-overview:latest";
        Map<String, Object> payload = Map.of("key", "value");

        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(viewName, snapshotKey))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should not throw
        service.persistSnapshot(viewName, snapshotKey, payload, "dashboard_refresh");

        assertThat(service.getSqlWriteFailureTotal()).isEqualTo(1);
        assertThat(service.getSqlWriteSuccessTotal()).isEqualTo(0);
        assertThat(service.getSqlLastFailureAt()).isNotNull();
    }

    /* --- Test methods: counters --- */

    @Test
    void countersTrackSuccessAndFailure() {
        assertThat(service.getSqlWriteSuccessTotal()).isZero();
        assertThat(service.getSqlWriteFailureTotal()).isZero();

        // Successful write
        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc("view", "view:latest"))
                .thenReturn(Optional.empty());
        service.persistSnapshot("view", "view:latest", Map.of("a", 1), "test");
        assertThat(service.getSqlWriteSuccessTotal()).isEqualTo(1);
        assertThat(service.getSqlLastWriteAt()).isNotNull();

        // Failed write
        when(repository.findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc("view", "view:latest"))
                .thenThrow(new RuntimeException("fail"));
        service.persistSnapshot("view", "view:latest", Map.of("a", 1), "test");
        assertThat(service.getSqlWriteFailureTotal()).isEqualTo(1);
        assertThat(service.getSqlLastFailureAt()).isNotNull();
    }

    /* --- Helper methods --- */

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
