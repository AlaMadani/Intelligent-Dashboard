-- Index for session_analysis search endpoint
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_search
    ON session_analysis (insured_id, is_anomaly, start_time DESC, end_time);
END;

-- Index for anomaly_events search endpoint
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ae_search' AND object_id = OBJECT_ID('anomaly_events'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ae_search
    ON anomaly_events (insured_id, anomaly_tier, anomaly_type, event_time DESC);
END;