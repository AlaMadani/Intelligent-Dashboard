IF COL_LENGTH('session_analysis', 'persona') IS NULL
    ALTER TABLE session_analysis ADD persona NVARCHAR(64) NULL;
IF COL_LENGTH('session_analysis', 'country_code') IS NULL
    ALTER TABLE session_analysis ADD country_code NVARCHAR(16) NULL;
IF COL_LENGTH('session_analysis', 'city') IS NULL
    ALTER TABLE session_analysis ADD city NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'month') IS NULL
    ALTER TABLE session_analysis ADD month NVARCHAR(16) NULL;
IF COL_LENGTH('session_analysis', 'session_number') IS NULL
    ALTER TABLE session_analysis ADD session_number INT NULL;
IF COL_LENGTH('session_analysis', 'first_action') IS NULL
    ALTER TABLE session_analysis ADD first_action NVARCHAR(512) NULL;
IF COL_LENGTH('session_analysis', 'last_action') IS NULL
    ALTER TABLE session_analysis ADD last_action NVARCHAR(512) NULL;
IF COL_LENGTH('session_analysis', 'first_route') IS NULL
    ALTER TABLE session_analysis ADD first_route NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'last_route') IS NULL
    ALTER TABLE session_analysis ADD last_route NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'unique_routes') IS NULL
    ALTER TABLE session_analysis ADD unique_routes INT NULL;
IF COL_LENGTH('session_analysis', 'unique_ips_used') IS NULL
    ALTER TABLE session_analysis ADD unique_ips_used INT NULL;
IF COL_LENGTH('session_analysis', 'unique_devices_used') IS NULL
    ALTER TABLE session_analysis ADD unique_devices_used INT NULL;
IF COL_LENGTH('session_analysis', 'total_kos') IS NULL
    ALTER TABLE session_analysis ADD total_kos INT NULL;
IF COL_LENGTH('session_analysis', 'total_oks') IS NULL
    ALTER TABLE session_analysis ADD total_oks INT NULL;
IF COL_LENGTH('session_analysis', 'longest_ko_streak') IS NULL
    ALTER TABLE session_analysis ADD longest_ko_streak INT NULL;
IF COL_LENGTH('session_analysis', 'min_inter_action_seconds') IS NULL
    ALTER TABLE session_analysis ADD min_inter_action_seconds FLOAT NULL;
IF COL_LENGTH('session_analysis', 'max_inter_action_seconds') IS NULL
    ALTER TABLE session_analysis ADD max_inter_action_seconds FLOAT NULL;
IF COL_LENGTH('session_analysis', 'has_login') IS NULL
    ALTER TABLE session_analysis ADD has_login BIT NULL;
IF COL_LENGTH('session_analysis', 'has_logout') IS NULL
    ALTER TABLE session_analysis ADD has_logout BIT NULL;
IF COL_LENGTH('session_analysis', 'ip_changed') IS NULL
    ALTER TABLE session_analysis ADD ip_changed BIT NULL;
IF COL_LENGTH('session_analysis', 'device_changed') IS NULL
    ALTER TABLE session_analysis ADD device_changed BIT NULL;
IF COL_LENGTH('session_analysis', 'total_download_actions') IS NULL
    ALTER TABLE session_analysis ADD total_download_actions INT NULL;
IF COL_LENGTH('session_analysis', 'max_downloads_in_2_minutes') IS NULL
    ALTER TABLE session_analysis ADD max_downloads_in_2_minutes INT NULL;
IF COL_LENGTH('session_analysis', 'ping_pong_count') IS NULL
    ALTER TABLE session_analysis ADD ping_pong_count INT NULL;
IF COL_LENGTH('session_analysis', 'risk_score_max') IS NULL
    ALTER TABLE session_analysis ADD risk_score_max FLOAT NULL;
IF COL_LENGTH('session_analysis', 'risk_score_avg') IS NULL
    ALTER TABLE session_analysis ADD risk_score_avg FLOAT NULL;
IF COL_LENGTH('session_analysis', 'ended_abruptly') IS NULL
    ALTER TABLE session_analysis ADD ended_abruptly BIT NULL;
IF COL_LENGTH('session_analysis', 'anomaly_event_count') IS NULL
    ALTER TABLE session_analysis ADD anomaly_event_count INT NULL;
IF COL_LENGTH('session_analysis', 'anomaly_types_json') IS NULL
    ALTER TABLE session_analysis ADD anomaly_types_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'campaign_ids_json') IS NULL
    ALTER TABLE session_analysis ADD campaign_ids_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'action_sequence_signature') IS NULL
    ALTER TABLE session_analysis ADD action_sequence_signature NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'route_sequence_signature') IS NULL
    ALTER TABLE session_analysis ADD route_sequence_signature NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'iso_score') IS NULL
    ALTER TABLE session_analysis ADD iso_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'anomaly_probability') IS NULL
    ALTER TABLE session_analysis ADD anomaly_probability FLOAT NULL;
IF COL_LENGTH('session_analysis', 'churn_probability') IS NULL
    ALTER TABLE session_analysis ADD churn_probability FLOAT NULL;
IF COL_LENGTH('session_analysis', 'ensemble_risk_score') IS NULL
    ALTER TABLE session_analysis ADD ensemble_risk_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'persona_cluster') IS NULL
    ALTER TABLE session_analysis ADD persona_cluster INT NULL;
IF COL_LENGTH('session_analysis', 'binary_detector_artifact') IS NULL
    ALTER TABLE session_analysis ADD binary_detector_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'path_deviation') IS NULL
    ALTER TABLE session_analysis ADD path_deviation BIT NULL;
IF COL_LENGTH('session_analysis', 'transition_probability') IS NULL
    ALTER TABLE session_analysis ADD transition_probability FLOAT NULL;
IF COL_LENGTH('session_analysis', 'transition_from_action') IS NULL
    ALTER TABLE session_analysis ADD transition_from_action NVARCHAR(512) NULL;
IF COL_LENGTH('session_analysis', 'transition_to_action') IS NULL
    ALTER TABLE session_analysis ADD transition_to_action NVARCHAR(512) NULL;
IF COL_LENGTH('session_analysis', 'updated_at') IS NULL
    ALTER TABLE session_analysis ADD updated_at DATETIME2 NULL;

IF COL_LENGTH('anomaly_events', 'anomaly_probability') IS NULL
    ALTER TABLE anomaly_events ADD anomaly_probability FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'anomaly_flag') IS NULL
    ALTER TABLE anomaly_events ADD anomaly_flag BIT NULL;
IF COL_LENGTH('anomaly_events', 'churn_probability') IS NULL
    ALTER TABLE anomaly_events ADD churn_probability FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'risk_score') IS NULL
    ALTER TABLE anomaly_events ADD risk_score FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'persona_cluster') IS NULL
    ALTER TABLE anomaly_events ADD persona_cluster INT NULL;
IF COL_LENGTH('anomaly_events', 'path_deviation') IS NULL
    ALTER TABLE anomaly_events ADD path_deviation BIT NULL;
IF COL_LENGTH('anomaly_events', 'transition_probability') IS NULL
    ALTER TABLE anomaly_events ADD transition_probability FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'transition_from_action') IS NULL
    ALTER TABLE anomaly_events ADD transition_from_action NVARCHAR(512) NULL;
IF COL_LENGTH('anomaly_events', 'transition_to_action') IS NULL
    ALTER TABLE anomaly_events ADD transition_to_action NVARCHAR(512) NULL;
IF COL_LENGTH('anomaly_events', 'model_artifact') IS NULL
    ALTER TABLE anomaly_events ADD model_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('anomaly_events', 'next_actions_json') IS NULL
    ALTER TABLE anomaly_events ADD next_actions_json NVARCHAR(MAX) NULL;
