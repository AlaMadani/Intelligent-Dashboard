-- Session-level analytics persisted after a session is closed and scored.
CREATE TABLE session_analysis (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    insured_id NVARCHAR(64) NOT NULL,
    session_id NVARCHAR(64) NOT NULL,
    start_time DATETIME2 NULL,
    end_time DATETIME2 NULL,
    session_length INT NULL,
    session_duration_seconds BIGINT NULL,
    unique_action_count INT NULL,
    ko_rate FLOAT NULL,
    mean_delta_seconds FLOAT NULL,
    action_diversity FLOAT NULL,
    action_counts_json NVARCHAR(MAX) NULL,
    ae_score FLOAT NULL,
    is_anomaly BIT NULL,
    anomaly_type NVARCHAR(64) NULL,
    type_confidence FLOAT NULL,
    top3_next_actions NVARCHAR(MAX) NULL,
    rule_triggered BIT NULL,
    rule_type NVARCHAR(MAX) NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Immutable anomaly alert history for audit and downstream consumers.
CREATE TABLE anomaly_events (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    insured_id NVARCHAR(64) NOT NULL,
    session_id NVARCHAR(64) NULL,
    event_id NVARCHAR(64) NULL,
    event_time DATETIME2 NULL,
    anomaly_tier NVARCHAR(16) NULL,
    anomaly_type NVARCHAR(64) NULL,
    anomaly_score FLOAT NULL,
    type_confidence FLOAT NULL,
    rule_type NVARCHAR(64) NULL,
    event_json NVARCHAR(MAX) NULL,
    detected_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

-- Daily action-volume history used by the trend prediction job.
CREATE TABLE action_stats_daily (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    stat_date DATE NOT NULL,
    action_id INT NOT NULL,
    action_label NVARCHAR(256) NULL,
    actual_count BIGINT NULL,
    predicted_count FLOAT NULL,
    rolling_mean_7 FLOAT NULL,
    rolling_std_7 FLOAT NULL,
    spike_alert BIT NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT uq_action_stats_daily UNIQUE (stat_date, action_id)
);

-- Latest next-action prediction kept per insured user.
CREATE TABLE next_action_predictions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    insured_id NVARCHAR(64) NOT NULL,
    session_id NVARCHAR(64) NULL,
    predicted_at DATETIME2 NULL,
    top3_actions_json NVARCHAR(MAX) NULL,
    CONSTRAINT uq_next_action_predictions UNIQUE (insured_id)
);

-- Rolling user-level risk profile derived from recent session history.
CREATE TABLE user_risk_profile (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    insured_id NVARCHAR(64) NOT NULL,
    last_updated DATETIME2 NULL,
    anomaly_count_7d INT NULL,
    anomaly_count_30d INT NULL,
    last_anomaly_type NVARCHAR(64) NULL,
    risk_tier NVARCHAR(16) NULL,
    anomaly_rate_30d FLOAT NULL,
    sessions_7d INT NULL,
    sessions_30d INT NULL,
    most_frequent_action_30d NVARCHAR(256) NULL,
    avg_session_duration_30d FLOAT NULL,
    consecutive_clean_sessions INT NULL,
    CONSTRAINT uq_user_risk_profile UNIQUE (insured_id)
);
