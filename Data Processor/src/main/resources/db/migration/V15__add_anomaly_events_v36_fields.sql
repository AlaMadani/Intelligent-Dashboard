-- Add anomaly_type_evidence_json that the API entity already references
IF COL_LENGTH('anomaly_events', 'anomaly_type_evidence_json') IS NULL
    ALTER TABLE anomaly_events ADD anomaly_type_evidence_json NVARCHAR(MAX) NULL;

-- Also add the remaining columns the API entity maps that V14 missed
IF COL_LENGTH('anomaly_events', 'v36_runtime_version') IS NULL
    ALTER TABLE anomaly_events ADD v36_runtime_version NVARCHAR(32) NULL;

IF COL_LENGTH('anomaly_events', 'xgboost_anomaly_score') IS NULL
    ALTER TABLE anomaly_events ADD xgboost_anomaly_score FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'xgboost_anomaly_score_100') IS NULL
    ALTER TABLE anomaly_events ADD xgboost_anomaly_score_100 FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'lightgbm_alert_score') IS NULL
    ALTER TABLE anomaly_events ADD lightgbm_alert_score FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'lightgbm_alert_score_100') IS NULL
    ALTER TABLE anomaly_events ADD lightgbm_alert_score_100 FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'transformer_risk_score_100') IS NULL
    ALTER TABLE anomaly_events ADD transformer_risk_score_100 FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'tcn_risk_score_100') IS NULL
    ALTER TABLE anomaly_events ADD tcn_risk_score_100 FLOAT NULL;

IF COL_LENGTH('anomaly_events', 'churn_risk_level') IS NULL
    ALTER TABLE anomaly_events ADD churn_risk_level NVARCHAR(32) NULL;

IF COL_LENGTH('anomaly_events', 'investigation_payload_json') IS NULL
    ALTER TABLE anomaly_events ADD investigation_payload_json NVARCHAR(MAX) NULL;

IF COL_LENGTH('anomaly_events', 'llm_explanation_evidence_payload_json') IS NULL
    ALTER TABLE anomaly_events ADD llm_explanation_evidence_payload_json NVARCHAR(MAX) NULL;

IF COL_LENGTH('anomaly_events', 'runtime_warnings_json') IS NULL
    ALTER TABLE anomaly_events ADD runtime_warnings_json NVARCHAR(MAX) NULL;