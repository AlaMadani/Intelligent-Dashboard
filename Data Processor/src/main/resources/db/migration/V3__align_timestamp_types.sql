-- Drop default constraint on created_at if it exists
BEGIN
    DECLARE @ConstraintName NVARCHAR(128);
    SELECT @ConstraintName = name
    FROM sys.default_constraints
    WHERE parent_object_id = OBJECT_ID('session_analysis')
      AND parent_column_id = (
        SELECT column_id 
        FROM sys.columns 
        WHERE object_id = OBJECT_ID('session_analysis') 
          AND name = 'created_at'
      );

    IF @ConstraintName IS NOT NULL
    BEGIN
        EXEC('ALTER TABLE session_analysis DROP CONSTRAINT ' + @ConstraintName);
    END;

    -- Alter created_at column type
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'session_analysis'
          AND COLUMN_NAME = 'created_at'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE session_analysis ALTER COLUMN created_at DATETIMEOFFSET(7) NOT NULL;
    END;

    -- Recreate the default constraint
    IF NOT EXISTS (
        SELECT name
        FROM sys.default_constraints
        WHERE parent_object_id = OBJECT_ID('session_analysis')
          AND parent_column_id = (
            SELECT column_id 
            FROM sys.columns 
            WHERE object_id = OBJECT_ID('session_analysis') 
              AND name = 'created_at'
          )
    )
    BEGIN
        ALTER TABLE session_analysis ADD DEFAULT SYSUTCDATETIME() FOR created_at;
    END;
END;

-- Alter updated_at column type
BEGIN
    IF EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME = 'session_analysis'
          AND COLUMN_NAME = 'updated_at'
          AND DATA_TYPE <> 'datetimeoffset'
    )
    BEGIN
        ALTER TABLE session_analysis ALTER COLUMN updated_at DATETIMEOFFSET(7) NULL;
    END;
END;
