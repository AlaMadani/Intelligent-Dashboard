-- Final comprehensive timestamp alignment
-- Handles all remaining Instant fields across all entities

-- Migrate user_risk_profile.last_updated to DATETIMEOFFSET(7)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'user_risk_profile'
          AND COLUMN_NAME = 'last_updated'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE user_risk_profile ALTER COLUMN last_updated DATETIMEOFFSET(7) NULL;
    END;
END;
