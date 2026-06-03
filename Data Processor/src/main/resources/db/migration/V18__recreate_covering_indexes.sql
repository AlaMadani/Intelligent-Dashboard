-- V18: Recreate indexes without the V1/V2 columns dropped in V16

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_search
    ON session_analysis (insured_id, start_time DESC, end_time);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search_covering' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_search_covering
    ON session_analysis (insured_id, start_time DESC, end_time)
    INCLUDE (
        id, session_id, created_at, session_duration_seconds, session_length,
        unique_action_count
    );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ae_search_covering' AND object_id = OBJECT_ID('anomaly_events'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ae_search_covering
    ON anomaly_events (insured_id, anomaly_tier, anomaly_type, event_time DESC)
    INCLUDE (
        id, session_id, event_id, anomaly_score, anomaly_probability,
        type_confidence, rule_type, churn_probability,
        risk_score, persona_cluster, detected_at
    );
END;