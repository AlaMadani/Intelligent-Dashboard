-- Complete timestamp alignment for all tables to DATETIMEOFFSET(7)
-- Handles action_stats_daily, anomaly_events, and next_action_predictions

-- Migrate action_stats_daily.created_at to DATETIMEOFFSET(7)
BEGIN
    DECLARE @ConstraintName1 NVARCHAR(128);
    SELECT @ConstraintName1 = name
    FROM sys.default_constraints
    WHERE parent_object_id = OBJECT_ID('action_stats_daily')
      AND parent_column_id = (
        SELECT column_id 
        FROM sys.columns 
        WHERE object_id = OBJECT_ID('action_stats_daily') 
          AND name = 'created_at'
      );

    IF @ConstraintName1 IS NOT NULL
    BEGIN
        EXEC('ALTER TABLE action_stats_daily DROP CONSTRAINT ' + @ConstraintName1);
    END;

    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'action_stats_daily'
          AND COLUMN_NAME = 'created_at'
          AND DATA_TYPE = 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE action_stats_daily ALTER COLUMN created_at DATETIMEOFFSET(7) NOT NULL;
        ALTER TABLE action_stats_daily ADD DEFAULT SYSUTCDATETIME() FOR created_at;
    END;
END;

-- Migrate anomaly_events.event_time to DATETIMEOFFSET(7)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'anomaly_events'
          AND COLUMN_NAME = 'event_time'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE anomaly_events ALTER COLUMN event_time DATETIMEOFFSET(7) NULL;
    END;
END;

-- Migrate anomaly_events.detected_at to DATETIMEOFFSET(7)
BEGIN
    DECLARE @ConstraintName2 NVARCHAR(128);
    SELECT @ConstraintName2 = name
    FROM sys.default_constraints
    WHERE parent_object_id = OBJECT_ID('anomaly_events')
      AND parent_column_id = (
        SELECT column_id 
        FROM sys.columns 
        WHERE object_id = OBJECT_ID('anomaly_events') 
          AND name = 'detected_at'
      );

    IF @ConstraintName2 IS NOT NULL
    BEGIN
        EXEC('ALTER TABLE anomaly_events DROP CONSTRAINT ' + @ConstraintName2);
    END;

    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'anomaly_events'
          AND COLUMN_NAME = 'detected_at'
          AND DATA_TYPE = 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE anomaly_events ALTER COLUMN detected_at DATETIMEOFFSET(7) NOT NULL;
        ALTER TABLE anomaly_events ADD DEFAULT SYSUTCDATETIME() FOR detected_at;
    END;
END;

-- Migrate next_action_predictions.predicted_at to DATETIMEOFFSET(7)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'next_action_predictions'
          AND COLUMN_NAME = 'predicted_at'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE next_action_predictions ALTER COLUMN predicted_at DATETIMEOFFSET(7) NULL;
    END;
END;
