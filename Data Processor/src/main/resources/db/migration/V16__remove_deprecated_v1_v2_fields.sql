-- V16: Remove deprecated V1/V2 columns that have no V3.6 relevance
-- These are superseded by V3.6+ fields or were never read by any V3.6 code path.

-- ============================================================
-- anomaly_events: drop V2 observability fields not used by V3.6
-- ============================================================
-- The covering index references several columns being dropped,
-- so it must be removed first. V18 recreates it without them.
IF EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_ae_search_covering' AND object_id = OBJECT_ID('anomaly_events'))
    DROP INDEX idx_ae_search_covering ON anomaly_events;

IF COL_LENGTH('anomaly_events', 'path_deviation') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN path_deviation;

IF COL_LENGTH('anomaly_events', 'transition_probability') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN transition_probability;

IF COL_LENGTH('anomaly_events', 'transition_from_action') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN transition_from_action;

IF COL_LENGTH('anomaly_events', 'transition_to_action') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN transition_to_action;

IF COL_LENGTH('anomaly_events', 'model_artifact') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN model_artifact;

IF COL_LENGTH('anomaly_events', 'next_actions_json') IS NOT NULL
    ALTER TABLE anomaly_events DROP COLUMN next_actions_json;

-- ============================================================
-- session_analysis: drop V1/V2 fields not relevant to V3.6
-- ============================================================
-- Both indexes reference columns being dropped, so they must be removed first.
-- V18 recreates them without the dropped columns.
IF EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search' AND object_id = OBJECT_ID('session_analysis'))
    DROP INDEX idx_sa_search ON session_analysis;

IF EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_search_covering' AND object_id = OBJECT_ID('session_analysis'))
    DROP INDEX idx_sa_search_covering ON session_analysis;

-- V2 persona (superseded by persona_label / persona_source / persona_confidence from V13)
IF COL_LENGTH('session_analysis', 'persona') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN persona;

IF COL_LENGTH('session_analysis', 'city') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN city;

IF COL_LENGTH('session_analysis', 'month') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN month;

IF COL_LENGTH('session_analysis', 'session_number') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN session_number;

-- V2 action-level identifiers (never read by API service)
IF COL_LENGTH('session_analysis', 'first_action') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN first_action;

IF COL_LENGTH('session_analysis', 'last_action') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN last_action;

IF COL_LENGTH('session_analysis', 'first_route') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN first_route;

IF COL_LENGTH('session_analysis', 'last_route') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN last_route;

-- V2 aggregate session stats (never read)
IF COL_LENGTH('session_analysis', 'unique_routes') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN unique_routes;

IF COL_LENGTH('session_analysis', 'unique_ips_used') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN unique_ips_used;

IF COL_LENGTH('session_analysis', 'unique_devices_used') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN unique_devices_used;

IF COL_LENGTH('session_analysis', 'total_kos') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN total_kos;

IF COL_LENGTH('session_analysis', 'total_oks') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN total_oks;

IF COL_LENGTH('session_analysis', 'longest_ko_streak') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN longest_ko_streak;

-- V1 statistical fields (superseded by V3.6 per-model scores)
IF COL_LENGTH('session_analysis', 'ko_rate') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN ko_rate;

IF COL_LENGTH('session_analysis', 'mean_delta_seconds') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN mean_delta_seconds;

IF COL_LENGTH('session_analysis', 'min_inter_action_seconds') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN min_inter_action_seconds;

IF COL_LENGTH('session_analysis', 'max_inter_action_seconds') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN max_inter_action_seconds;

IF COL_LENGTH('session_analysis', 'action_diversity') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN action_diversity;

-- V2 boolean flags (never read by API service)
IF COL_LENGTH('session_analysis', 'has_login') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN has_login;

IF COL_LENGTH('session_analysis', 'has_logout') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN has_logout;

IF COL_LENGTH('session_analysis', 'ip_changed') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN ip_changed;

IF COL_LENGTH('session_analysis', 'device_changed') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN device_changed;

-- V2 aggregate counts (never read by API service)
IF COL_LENGTH('session_analysis', 'total_download_actions') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN total_download_actions;

IF COL_LENGTH('session_analysis', 'max_downloads_in_2_minutes') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN max_downloads_in_2_minutes;

IF COL_LENGTH('session_analysis', 'ping_pong_count') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN ping_pong_count;

-- V2 risk score aggregates (never read; V3.6 uses final_risk_score)
IF COL_LENGTH('session_analysis', 'risk_score_max') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN risk_score_max;

IF COL_LENGTH('session_analysis', 'risk_score_avg') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN risk_score_avg;

IF COL_LENGTH('session_analysis', 'ended_abruptly') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN ended_abruptly;

IF COL_LENGTH('session_analysis', 'anomaly_event_count') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN anomaly_event_count;

-- V2 JSON metadata blobs (never read by API service)
IF COL_LENGTH('session_analysis', 'anomaly_types_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN anomaly_types_json;

IF COL_LENGTH('session_analysis', 'campaign_ids_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN campaign_ids_json;

IF COL_LENGTH('session_analysis', 'action_sequence_signature') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN action_sequence_signature;

IF COL_LENGTH('session_analysis', 'route_sequence_signature') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN route_sequence_signature;

-- V2/V13 scores superseded by V3.6 equivalents
IF COL_LENGTH('session_analysis', 'iso_score') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN iso_score;

IF COL_LENGTH('session_analysis', 'is_anomaly') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN is_anomaly;

IF COL_LENGTH('session_analysis', 'anomaly_type') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN anomaly_type;

IF COL_LENGTH('session_analysis', 'type_confidence') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN type_confidence;

IF COL_LENGTH('session_analysis', 'anomaly_probability') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN anomaly_probability;

IF COL_LENGTH('session_analysis', 'ensemble_risk_score') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN ensemble_risk_score;

IF COL_LENGTH('session_analysis', 'binary_detector_artifact') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN binary_detector_artifact;

-- V13 raw model scores superseded by V14 surprise/risk scores
IF COL_LENGTH('session_analysis', 'transformer_score') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN transformer_score;

IF COL_LENGTH('session_analysis', 'tcn_score') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN tcn_score;

-- V13/V14 artifact names stored per-model; never read by API service
IF COL_LENGTH('session_analysis', 'xgboost_artifact') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN xgboost_artifact;

IF COL_LENGTH('session_analysis', 'lightgbm_artifact') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN lightgbm_artifact;

-- JSON debug blobs never consumed by the V3.6 API service
IF COL_LENGTH('session_analysis', 'sequence_contributions_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN sequence_contributions_json;

IF COL_LENGTH('session_analysis', 'feature_contributions_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN feature_contributions_json;

IF COL_LENGTH('session_analysis', 'explainability_text') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN explainability_text;

IF COL_LENGTH('session_analysis', 'risk_fusion_weights_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN risk_fusion_weights_json;

IF COL_LENGTH('session_analysis', 'context_tags_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN context_tags_json;

IF COL_LENGTH('session_analysis', 'persona_warnings_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN persona_warnings_json;

IF COL_LENGTH('session_analysis', 'rare_transitions_json') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN rare_transitions_json;

-- V2 path-deviation / transition fields (V3.6 uses anomaly type attribution instead)
IF COL_LENGTH('session_analysis', 'path_deviation') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN path_deviation;

IF COL_LENGTH('session_analysis', 'transition_probability') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN transition_probability;

IF COL_LENGTH('session_analysis', 'transition_from_action') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN transition_from_action;

IF COL_LENGTH('session_analysis', 'transition_to_action') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN transition_to_action;

-- V1 next-action / rule tracking (never read)
IF COL_LENGTH('session_analysis', 'top3_next_actions') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN top3_next_actions;

IF COL_LENGTH('session_analysis', 'rule_triggered') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN rule_triggered;

IF COL_LENGTH('session_analysis', 'rule_type') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN rule_type;

IF COL_LENGTH('session_analysis', 'updated_at') IS NOT NULL
    ALTER TABLE session_analysis DROP COLUMN updated_at;