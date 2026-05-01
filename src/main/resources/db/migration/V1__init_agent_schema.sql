CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NULL,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    turn_no INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    last_error TEXT NULL
);

CREATE TABLE IF NOT EXISTS agent_message (
    message_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content MEDIUMTEXT NULL,
    tool_use_id VARCHAR(128) NULL,
    tool_calls JSON NULL,
    extras JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_agent_message_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    UNIQUE KEY uk_agent_message_run_seq (run_id, seq),
    KEY idx_agent_message_run (run_id)
);

CREATE TABLE IF NOT EXISTS agent_llm_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    turn_no INT NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    finish_reason VARCHAR(40) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    error_json JSON NULL,
    raw_diagnostic_json JSON NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_agent_llm_attempt_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    KEY idx_agent_llm_attempt_run (run_id, turn_no)
);

CREATE TABLE IF NOT EXISTS agent_tool_call_trace (
    tool_call_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    tool_use_id VARCHAR(128) NOT NULL,
    raw_tool_name VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    args_json JSON NOT NULL,
    is_concurrent BOOLEAN NOT NULL,
    precheck_failed BOOLEAN NOT NULL DEFAULT FALSE,
    precheck_error_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_agent_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_agent_tool_call_message FOREIGN KEY (message_id) REFERENCES agent_message(message_id),
    UNIQUE KEY uk_agent_tool_call_use_id (run_id, tool_use_id),
    KEY idx_agent_tool_call_run (run_id, seq)
);

CREATE TABLE IF NOT EXISTS agent_tool_result_trace (
    result_id VARCHAR(64) PRIMARY KEY,
    tool_call_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_use_id VARCHAR(128) NOT NULL,
    status VARCHAR(40) NOT NULL,
    result_json JSON NULL,
    error_json JSON NULL,
    cancel_reason VARCHAR(40) NULL,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_agent_tool_result_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_agent_tool_result_call FOREIGN KEY (tool_call_id) REFERENCES agent_tool_call_trace(tool_call_id),
    UNIQUE KEY uk_agent_tool_result_call (tool_call_id),
    KEY idx_agent_tool_result_run (run_id)
);

CREATE TABLE IF NOT EXISTS user_profile (
    user_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    phone VARCHAR(40) NULL,
    email VARCHAR(128) NULL,
    address VARCHAR(255) NULL,
    role_name VARCHAR(64) NOT NULL DEFAULT 'buyer'
);

CREATE TABLE IF NOT EXISTS business_order (
    order_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    item_name VARCHAR(255) NOT NULL,
    cancel_reason VARCHAR(255) NULL,
    cancelled_at TIMESTAMP(3) NULL,
    KEY idx_business_order_user_created (user_id, created_at),
    KEY idx_business_order_user_status (user_id, status)
);

INSERT INTO user_profile (user_id, display_name, phone, email, address, role_name)
VALUES ('demo-user', 'Demo Buyer', '13800138000', 'demo@example.com', 'Shanghai', 'buyer')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO business_order (order_id, user_id, status, created_at, amount, item_name)
VALUES
    ('O-1001', 'demo-user', 'PAID', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY), 129.90, 'Noise cancelling earbuds'),
    ('O-1002', 'demo-user', 'SHIPPED', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 DAY), 89.00, 'USB-C hub'),
    ('O-1003', 'demo-user', 'CANCELLED', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY), 59.90, 'Notebook stand')
ON DUPLICATE KEY UPDATE order_id = VALUES(order_id);
