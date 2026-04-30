-- Cleanup dead schema elements never used by the Data Processor.
-- ae_score was replaced by iso_score (V2) and is never written.
-- action_stats_daily was created but no code writes to or reads from it;
-- all event statistics go through Redis counters.

BEGIN
    IF COL_LENGTH('session_analysis', 'ae_score') IS NOT NULL
    BEGIN
        ALTER TABLE session_analysis DROP COLUMN ae_score;
    END
END;

BEGIN
    IF OBJECT_ID('action_stats_daily', 'U') IS NOT NULL
    BEGIN
        DROP TABLE action_stats_daily;
    END
END;