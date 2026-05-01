DELIMITER //

CREATE PROCEDURE add_context_compaction_attempt_scope()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_context_compaction'
          AND column_name = 'turn_no'
    ) THEN
        ALTER TABLE agent_context_compaction
            ADD COLUMN turn_no INT NULL AFTER run_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_context_compaction'
          AND column_name = 'attempt_id'
    ) THEN
        ALTER TABLE agent_context_compaction
            ADD COLUMN attempt_id VARCHAR(64) NULL AFTER turn_no;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_context_compaction'
          AND index_name = 'idx_agent_context_compaction_attempt'
    ) THEN
        ALTER TABLE agent_context_compaction
            ADD KEY idx_agent_context_compaction_attempt (attempt_id);
    END IF;
END//

CALL add_context_compaction_attempt_scope()//

DROP PROCEDURE add_context_compaction_attempt_scope//

DELIMITER ;

UPDATE agent_context_compaction
SET turn_no = COALESCE(turn_no, 0),
    attempt_id = COALESCE(NULLIF(attempt_id, ''), 'unattributed');

ALTER TABLE agent_context_compaction
    MODIFY COLUMN turn_no INT NOT NULL,
    MODIFY COLUMN attempt_id VARCHAR(64) NOT NULL;
