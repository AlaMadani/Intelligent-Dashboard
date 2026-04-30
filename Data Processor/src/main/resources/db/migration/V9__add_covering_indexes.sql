-- Covering index for session_analysis search endpoint - eliminates key lookups
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search_covering' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_search_covering
    ON session_analysis (insured_id, is_anomaly, start_time DESC, end_time)
    INCLUDE (
        id, session_id, created_at, session_duration_seconds, session_length,
        unique_action_count, path_deviation, risk_score_max, anomaly_probability,
        anomaly_type, rule_type, transition_probability
    );
END;

-- Covering index for anomaly_events search endpoint - eliminates key lookups
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ae_search_covering' AND object_id = OBJECT_ID('anomaly_events'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_ae_search_covering
    ON anomaly_events (insured_id, anomaly_tier, anomaly_type, event_time DESC)
    INCLUDE (
        id, session_id, event_id, anomaly_score, anomaly_probability,
        type_confidence, rule_type, churn_probability,
        risk_score, persona_cluster, path_deviation, transition_probability,
        transition_from_action, transition_to_action, model_artifact,
        detected_at
    );
END;
