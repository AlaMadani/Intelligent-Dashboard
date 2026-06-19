-- V19: Add indexes for dashboard refresh queries
-- findTopRiskySessions: ORDER BY final_risk_score DESC, created_at DESC
-- findTop50ByOrderByCreatedAtDesc: ORDER BY created_at DESC

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_risk_score' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_risk_score
    ON session_analysis (final_risk_score DESC, created_at DESC);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sa_created_at' AND object_id = OBJECT_ID('session_analysis'))
BEGIN
    CREATE NONCLUSTERED INDEX idx_sa_created_at
    ON session_analysis (created_at DESC);
END;