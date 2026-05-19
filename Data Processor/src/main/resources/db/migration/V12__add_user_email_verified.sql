IF COL_LENGTH('dbo.users', 'email_verified') IS NULL
BEGIN
    ALTER TABLE dbo.[users]
        ADD email_verified BIT NOT NULL CONSTRAINT df_users_email_verified DEFAULT 0;
END;
