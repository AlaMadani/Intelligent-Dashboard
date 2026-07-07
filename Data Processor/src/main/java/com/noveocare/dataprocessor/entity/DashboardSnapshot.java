package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "dashboard_snapshots", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"view_name", "snapshot_key"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schema_version", nullable = false, length = 32)
    private String schemaVersion;

    @Column(name = "view_name", nullable = false, length = 128)
    private String viewName;

    @Column(name = "snapshot_key", nullable = false, length = 256)
    private String snapshotKey;

    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "snapshot_timestamp", nullable = false, columnDefinition = "DATETIME2")
    private Instant snapshotTimestamp;

    @Column(name = "payload_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String payloadJson;

    @Column(name = "payload_hash", length = 128)
    private String payloadHash;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME2")
    private Instant updatedAt;

    @Column(name = "expires_at", columnDefinition = "DATETIME2")
    private Instant expiresAt;
}
