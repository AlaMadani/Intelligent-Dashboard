-- V21: Add llm_explanations table for LLM explanation persistence
-- Provides SQL fallback for api-service when Redis cache misses
-- Managed by data-processor schema migrations; consumed by api-service

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[llm_explanations]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[llm_explanations] (
        id                    BIGINT IDENTITY(1,1)   PRIMARY KEY,
        schema_version        NVARCHAR(32)           NULL,
        event_id              NVARCHAR(128)          NOT NULL,
        insured_id            NVARCHAR(128)          NULL,
        session_id            NVARCHAR(256)          NULL,

        language              NVARCHAR(32)           NULL,
        style                 NVARCHAR(64)           NULL,
        include_recommended_actions BIT              NOT NULL DEFAULT 1,

        provider              NVARCHAR(64)           NULL,
        model                 NVARCHAR(128)          NULL,
        prompt_version        NVARCHAR(64)           NULL,
        evidence_hash         NVARCHAR(128)          NULL,

        explanation_json      NVARCHAR(MAX)          NOT NULL,
        summary               NVARCHAR(MAX)          NULL,

        is_current            BIT                    NOT NULL DEFAULT 1,
        generation_count      INT                    NOT NULL DEFAULT 1,

        created_at            DATETIME2              NOT NULL,
        updated_at            DATETIME2              NOT NULL,
        expires_at            DATETIME2              NULL
    );

    CREATE INDEX [idx_llm_explanations_current]
        ON [dbo].[llm_explanations] (event_id, language, style, include_recommended_actions, evidence_hash)
        WHERE is_current = 1;

    CREATE INDEX [idx_llm_explanations_event_latest]
        ON [dbo].[llm_explanations] (event_id, is_current)
        INCLUDE (updated_at);
END;
