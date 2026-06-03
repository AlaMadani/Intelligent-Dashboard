-- V17: Add missing V3.6 fields to session_analysis that the entity now maps
-- These were identified as gaps during the storage audit and added to the entity.

IF COL_LENGTH('session_analysis', 'catboost_anomaly_score_100') IS NULL
    ALTER TABLE session_analysis ADD catboost_anomaly_score_100 FLOAT NULL;

IF COL_LENGTH('session_analysis', 'oneclasssvm_novelty_score_100') IS NULL
    ALTER TABLE session_analysis ADD oneclasssvm_novelty_score_100 FLOAT NULL;

IF COL_LENGTH('session_analysis', 'fallback_mode') IS NULL
    ALTER TABLE session_analysis ADD fallback_mode NVARCHAR(32) NULL;

IF COL_LENGTH('session_analysis', 'forecast_total_events_model') IS NULL
    ALTER TABLE session_analysis ADD forecast_total_events_model NVARCHAR(64) NULL;

IF COL_LENGTH('session_analysis', 'forecast_anomaly_rate_model') IS NULL
    ALTER TABLE session_analysis ADD forecast_anomaly_rate_model NVARCHAR(64) NULL;

IF COL_LENGTH('session_analysis', 'top_contributing_features_json') IS NULL
    ALTER TABLE session_analysis ADD top_contributing_features_json NVARCHAR(MAX) NULL;