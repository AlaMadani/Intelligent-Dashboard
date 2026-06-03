IF COL_LENGTH('session_analysis', 'v36_runtime_version') IS NULL
    ALTER TABLE session_analysis ADD v36_runtime_version NVARCHAR(32) NULL;
IF COL_LENGTH('session_analysis', 'xgboost_anomaly_score') IS NULL
    ALTER TABLE session_analysis ADD xgboost_anomaly_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'xgboost_anomaly_score_100') IS NULL
    ALTER TABLE session_analysis ADD xgboost_anomaly_score_100 FLOAT NULL;
IF COL_LENGTH('session_analysis', 'xgboost_artifact') IS NULL
    ALTER TABLE session_analysis ADD xgboost_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'lightgbm_alert_score') IS NULL
    ALTER TABLE session_analysis ADD lightgbm_alert_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'lightgbm_alert_score_100') IS NULL
    ALTER TABLE session_analysis ADD lightgbm_alert_score_100 FLOAT NULL;
IF COL_LENGTH('session_analysis', 'lightgbm_artifact') IS NULL
    ALTER TABLE session_analysis ADD lightgbm_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'catboost_anomaly_score') IS NULL
    ALTER TABLE session_analysis ADD catboost_anomaly_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'oneclasssvm_novelty_score') IS NULL
    ALTER TABLE session_analysis ADD oneclasssvm_novelty_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'transformer_surprise_score') IS NULL
    ALTER TABLE session_analysis ADD transformer_surprise_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'transformer_risk_score_100') IS NULL
    ALTER TABLE session_analysis ADD transformer_risk_score_100 FLOAT NULL;
IF COL_LENGTH('session_analysis', 'transformer_artifact') IS NULL
    ALTER TABLE session_analysis ADD transformer_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'tcn_surprise_score') IS NULL
    ALTER TABLE session_analysis ADD tcn_surprise_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'tcn_risk_score_100') IS NULL
    ALTER TABLE session_analysis ADD tcn_risk_score_100 FLOAT NULL;
IF COL_LENGTH('session_analysis', 'tcn_artifact') IS NULL
    ALTER TABLE session_analysis ADD tcn_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'selected_sequence_model') IS NULL
    ALTER TABLE session_analysis ADD selected_sequence_model NVARCHAR(32) NULL;
IF COL_LENGTH('session_analysis', 'top_sequence_surprise_fields_json') IS NULL
    ALTER TABLE session_analysis ADD top_sequence_surprise_fields_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'triggered_rules_json') IS NULL
    ALTER TABLE session_analysis ADD triggered_rules_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'rule_contributions_json') IS NULL
    ALTER TABLE session_analysis ADD rule_contributions_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'business_context_score') IS NULL
    ALTER TABLE session_analysis ADD business_context_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'aggregation_boost') IS NULL
    ALTER TABLE session_analysis ADD aggregation_boost FLOAT NULL;
IF COL_LENGTH('session_analysis', 'risk_fusion_weights_json') IS NULL
    ALTER TABLE session_analysis ADD risk_fusion_weights_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'model_contributions_json') IS NULL
    ALTER TABLE session_analysis ADD model_contributions_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'risk_level') IS NULL
    ALTER TABLE session_analysis ADD risk_level NVARCHAR(32) NULL;
IF COL_LENGTH('session_analysis', 'anomaly_type_source') IS NULL
    ALTER TABLE session_analysis ADD anomaly_type_source NVARCHAR(64) NULL;
IF COL_LENGTH('session_analysis', 'anomaly_type_confidence') IS NULL
    ALTER TABLE session_analysis ADD anomaly_type_confidence FLOAT NULL;
IF COL_LENGTH('session_analysis', 'anomaly_type_evidence_json') IS NULL
    ALTER TABLE session_analysis ADD anomaly_type_evidence_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'persona_warnings_json') IS NULL
    ALTER TABLE session_analysis ADD persona_warnings_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'churn_model_name') IS NULL
    ALTER TABLE session_analysis ADD churn_model_name NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'churn_model_artifact') IS NULL
    ALTER TABLE session_analysis ADD churn_model_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'churn_feature_warnings_json') IS NULL
    ALTER TABLE session_analysis ADD churn_feature_warnings_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'forecast_context_json') IS NULL
    ALTER TABLE session_analysis ADD forecast_context_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'llm_explanation_evidence_payload_json') IS NULL
    ALTER TABLE session_analysis ADD llm_explanation_evidence_payload_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'runtime_warnings_json') IS NULL
    ALTER TABLE session_analysis ADD runtime_warnings_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'investigation_payload_json') IS NULL
    ALTER TABLE session_analysis ADD investigation_payload_json NVARCHAR(MAX) NULL;

IF COL_LENGTH('anomaly_events', 'schema_version') IS NULL
    ALTER TABLE anomaly_events ADD schema_version NVARCHAR(32) NULL;
IF COL_LENGTH('anomaly_events', 'record_id') IS NULL
    ALTER TABLE anomaly_events ADD record_id NVARCHAR(128) NULL;
IF COL_LENGTH('anomaly_events', 'risk_level') IS NULL
    ALTER TABLE anomaly_events ADD risk_level NVARCHAR(32) NULL;
IF COL_LENGTH('anomaly_events', 'anomaly_type_confidence') IS NULL
    ALTER TABLE anomaly_events ADD anomaly_type_confidence FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'model_scores_json') IS NULL
    ALTER TABLE anomaly_events ADD model_scores_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'model_contributions_json') IS NULL
    ALTER TABLE anomaly_events ADD model_contributions_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'triggered_rules_json') IS NULL
    ALTER TABLE anomaly_events ADD triggered_rules_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'churn_context_json') IS NULL
    ALTER TABLE anomaly_events ADD churn_context_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'persona_context_json') IS NULL
    ALTER TABLE anomaly_events ADD persona_context_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'artifact_names_json') IS NULL
    ALTER TABLE anomaly_events ADD artifact_names_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('anomaly_events', 'llm_evidence_payload_available') IS NULL
    ALTER TABLE anomaly_events ADD llm_evidence_payload_available BIT NULL;
IF COL_LENGTH('anomaly_events', 'llm_evidence_payload_redis_key') IS NULL
    ALTER TABLE anomaly_events ADD llm_evidence_payload_redis_key NVARCHAR(256) NULL;
