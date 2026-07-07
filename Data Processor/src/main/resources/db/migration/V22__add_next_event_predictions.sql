-- V22: Add next_event_predictions table for session-scoped next-event predictions
-- Stores decoded top-K predictions from the sequence model per session+context
-- Used by api-service for User360 and Alert Investigation

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[next_event_predictions]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[next_event_predictions] (
        id                    BIGINT IDENTITY(1,1)   PRIMARY KEY,
        insured_id            NVARCHAR(128)          NOT NULL,
        session_id            NVARCHAR(256)          NOT NULL,
        context_event_id      NVARCHAR(128)          NULL,
        context_size          INT                    NOT NULL DEFAULT 0,
        model_name            NVARCHAR(64)           NULL,
        predictions_json      NVARCHAR(MAX)          NOT NULL,
        created_at            DATETIME2              NOT NULL,
        CONSTRAINT uq_next_event_predictions UNIQUE (session_id, context_event_id)
    );

    CREATE INDEX [idx_next_event_predictions_insured]
        ON [dbo].[next_event_predictions] (insured_id)
        INCLUDE (created_at);

    CREATE INDEX [idx_next_event_predictions_session]
        ON [dbo].[next_event_predictions] (session_id)
        INCLUDE (created_at);
END;
