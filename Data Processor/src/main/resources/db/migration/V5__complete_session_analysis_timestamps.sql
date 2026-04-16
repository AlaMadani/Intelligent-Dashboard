-- Complete session_analysis timestamp alignment
-- Migrate start_time and end_time to DATETIMEOFFSET(7)

-- Alter start_time column type
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'session_analysis'
          AND COLUMN_NAME = 'start_time'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE session_analysis ALTER COLUMN start_time DATETIMEOFFSET(7) NULL;
    END;
END;

-- Alter end_time column type
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'session_analysis'
          AND COLUMN_NAME = 'end_time'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE session_analysis ALTER COLUMN end_time DATETIMEOFFSET(7) NULL;
    END;
END;
