package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Read-only materialised view of a dashboard at a specific point in time.
 * Each row stores the full JSON payload for a given dashboard view on a
 * given date, enabling historical comparisons and point-in-time reporting.
 */
@Entity
@Table(name = "dashboard_snapshots")
@Data
@Immutable
public class DashboardSnapshot {

    /** Unique identifier for the snapshot record. */
    @Id
    private Long id;

    /** Version of the schema used to serialise the payload (forward compatibility). */
    @Column(name = "schema_version")
    private String schemaVersion;

    /** Logical name of the dashboard view this snapshot represents. */
    @Column(name = "view_name")
    private String viewName;

    /** Unique key identifying this specific snapshot within the view. */
    @Column(name = "snapshot_key")
    private String snapshotKey;

    /** Calendar date to which this snapshot applies. */
    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    /** Precise instant when the snapshot was taken. */
    @Column(name = "snapshot_timestamp")
    private Instant snapshotTimestamp;

    /** Complete serialised dashboard state as a JSON string. */
    @Column(name = "payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String payloadJson;

    /** Hash of the payload for integrity verification and change detection. */
    @Column(name = "payload_hash")
    private String payloadHash;

    /** Identifier of the system or component that produced this snapshot. */
    @Column(name = "source")
    private String source;

    /** Timestamp when the snapshot record was first inserted. */
    @Column(name = "created_at")
    private Instant createdAt;

    /** Timestamp when the snapshot record was last updated. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Instant after which the snapshot is considered stale and may be purged. */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
