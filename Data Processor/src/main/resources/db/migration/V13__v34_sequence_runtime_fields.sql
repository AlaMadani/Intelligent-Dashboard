IF COL_LENGTH('session_analysis', 'sequence_model_artifact') IS NULL
    ALTER TABLE session_analysis ADD sequence_model_artifact NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'sequence_anomaly_score') IS NULL
    ALTER TABLE session_analysis ADD sequence_anomaly_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'sequence_cat_score') IS NULL
    ALTER TABLE session_analysis ADD sequence_cat_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'sequence_cont_score') IS NULL
    ALTER TABLE session_analysis ADD sequence_cont_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'sequence_ctx_score') IS NULL
    ALTER TABLE session_analysis ADD sequence_ctx_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'ai_risk_score') IS NULL
    ALTER TABLE session_analysis ADD ai_risk_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'rule_risk_score') IS NULL
    ALTER TABLE session_analysis ADD rule_risk_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'final_risk_score') IS NULL
    ALTER TABLE session_analysis ADD final_risk_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'transformer_score') IS NULL
    ALTER TABLE session_analysis ADD transformer_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'tcn_score') IS NULL
    ALTER TABLE session_analysis ADD tcn_score FLOAT NULL;
IF COL_LENGTH('session_analysis', 'persona_label') IS NULL
    ALTER TABLE session_analysis ADD persona_label NVARCHAR(128) NULL;
IF COL_LENGTH('session_analysis', 'persona_source') IS NULL
    ALTER TABLE session_analysis ADD persona_source NVARCHAR(64) NULL;
IF COL_LENGTH('session_analysis', 'persona_confidence') IS NULL
    ALTER TABLE session_analysis ADD persona_confidence FLOAT NULL;
IF COL_LENGTH('session_analysis', 'churn_risk_level') IS NULL
    ALTER TABLE session_analysis ADD churn_risk_level NVARCHAR(32) NULL;
IF COL_LENGTH('session_analysis', 'model_artifacts_json') IS NULL
    ALTER TABLE session_analysis ADD model_artifacts_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'sequence_contributions_json') IS NULL
    ALTER TABLE session_analysis ADD sequence_contributions_json NVARCHAR(MAX) NULL;

IF COL_LENGTH('anomaly_events', 'persona_label') IS NULL
    ALTER TABLE anomaly_events ADD persona_label NVARCHAR(128) NULL;
IF COL_LENGTH('anomaly_events', 'ai_risk_score') IS NULL
    ALTER TABLE anomaly_events ADD ai_risk_score FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'rule_risk_score') IS NULL
    ALTER TABLE anomaly_events ADD rule_risk_score FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'final_risk_score') IS NULL
    ALTER TABLE anomaly_events ADD final_risk_score FLOAT NULL;
IF COL_LENGTH('anomaly_events', 'anomaly_type_source') IS NULL
    ALTER TABLE anomaly_events ADD anomaly_type_source NVARCHAR(64) NULL;
