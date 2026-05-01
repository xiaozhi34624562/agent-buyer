DELIMITER //

CREATE PROCEDURE add_run_context_provider_fields()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_run_context'
          AND column_name = 'primary_provider'
    ) THEN
        ALTER TABLE agent_run_context
            ADD COLUMN primary_provider VARCHAR(40) NULL AFTER model;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_run_context'
          AND column_name = 'fallback_provider'
    ) THEN
        ALTER TABLE agent_run_context
            ADD COLUMN fallback_provider VARCHAR(40) NULL AFTER primary_provider;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_run_context'
          AND column_name = 'provider_options'
    ) THEN
        ALTER TABLE agent_run_context
            ADD COLUMN provider_options JSON NULL AFTER fallback_provider;
    END IF;
END//

CALL add_run_context_provider_fields()//

DROP PROCEDURE add_run_context_provider_fields//

DELIMITER ;

UPDATE agent_run_context
SET primary_provider = COALESCE(NULLIF(primary_provider, ''), 'deepseek'),
    fallback_provider = COALESCE(NULLIF(fallback_provider, ''), COALESCE(NULLIF(primary_provider, ''), 'deepseek')),
    provider_options = COALESCE(provider_options, JSON_OBJECT('fallbackEnabled', false, 'legacyBackfill', true));

ALTER TABLE agent_run_context
    MODIFY COLUMN primary_provider VARCHAR(40) NOT NULL,
    MODIFY COLUMN fallback_provider VARCHAR(40) NOT NULL,
    MODIFY COLUMN provider_options JSON NOT NULL;
