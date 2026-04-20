IF COL_LENGTH('session_analysis', 'action_sequence_json') IS NULL
    ALTER TABLE session_analysis ADD action_sequence_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'route_sequence_json') IS NULL
    ALTER TABLE session_analysis ADD route_sequence_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'feature_contributions_json') IS NULL
    ALTER TABLE session_analysis ADD feature_contributions_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'explainability_text') IS NULL
    ALTER TABLE session_analysis ADD explainability_text NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'warnings_json') IS NULL
    ALTER TABLE session_analysis ADD warnings_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'triggered_rules_json') IS NULL
    ALTER TABLE session_analysis ADD triggered_rules_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'context_tags_json') IS NULL
    ALTER TABLE session_analysis ADD context_tags_json NVARCHAR(MAX) NULL;
IF COL_LENGTH('session_analysis', 'rare_transitions_json') IS NULL
    ALTER TABLE session_analysis ADD rare_transitions_json NVARCHAR(MAX) NULL;
