-- V20: Add dashboard_snapshots table for durable dashboard snapshot persistence
-- Provides SQL fallback for api-service when Redis cache misses

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'dashboard_snapshots') AND type = 'U')
BEGIN
    CREATE TABLE dashboard_snapshots (
        id BIGINT IDENTITY PRIMARY KEY,
        schema_version NVARCHAR(32) NOT NULL,
        view_name NVARCHAR(128) NOT NULL,
        snapshot_key NVARCHAR(256) NOT NULL,
        snapshot_date DATE NULL,
        snapshot_timestamp DATETIME2 NOT NULL,
        payload_json NVARCHAR(MAX) NOT NULL,
        payload_hash NVARCHAR(128) NULL,
        source NVARCHAR(64) NULL,
        created_at DATETIME2 NOT NULL,
        updated_at DATETIME2 NOT NULL,
        expires_at DATETIME2 NULL
    );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ds_view_key' AND object_id = OBJECT_ID('dashboard_snapshots'))
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX idx_ds_view_key
    ON dashboard_snapshots (view_name, snapshot_key);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ds_view_timestamp' AND object_id = OBJECT_ID('dashboard_snapshots'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ds_view_timestamp
    ON dashboard_snapshots (view_name, snapshot_timestamp DESC);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ds_snapshot_date' AND object_id = OBJECT_ID('dashboard_snapshots'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ds_snapshot_date
    ON dashboard_snapshots (snapshot_date);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ds_updated_at' AND object_id = OBJECT_ID('dashboard_snapshots'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ds_updated_at
    ON dashboard_snapshots (updated_at DESC);
END;
