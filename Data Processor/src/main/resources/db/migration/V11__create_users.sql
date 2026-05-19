IF NOT EXISTS (
    SELECT 1
    FROM sys.tables
    WHERE name = 'users'
      AND schema_id = SCHEMA_ID('dbo')
)
BEGIN
    CREATE TABLE dbo.[users] (
        id BIGINT IDENTITY(1,1) NOT NULL,
        email NVARCHAR(320) NOT NULL,
        password NVARCHAR(255) NOT NULL,
        full_name NVARCHAR(100) NOT NULL,
        role NVARCHAR(32) NOT NULL,
        enabled BIT NOT NULL CONSTRAINT df_users_enabled DEFAULT 1,
        created_at DATETIME2(6) NOT NULL,
        updated_at DATETIME2(6) NULL,
        CONSTRAINT pk_users PRIMARY KEY (id),
        CONSTRAINT uk_users_email UNIQUE (email)
    );
END;
