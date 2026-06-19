package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "dashboard_snapshots")
@Data
@Immutable
public class DashboardSnapshot {

    @Id
    private Long id;

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "view_name")
    private String viewName;

    @Column(name = "snapshot_key")
    private String snapshotKey;

    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "snapshot_timestamp")
    private Instant snapshotTimestamp;

    @Column(name = "payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String payloadJson;

    @Column(name = "payload_hash")
    private String payloadHash;

    @Column(name = "source")
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
