DROP TABLE IF EXISTS agent_artifact;
DROP TABLE IF EXISTS agent_state;
DROP TABLE IF EXISTS workflow_state;

DROP TABLE IF EXISTS user_customer_scope;
DROP TABLE IF EXISTS person;

CREATE TABLE person (
    person_id BIGINT PRIMARY KEY,
    full_name VARCHAR(255),
    display_name VARCHAR(255)
);

CREATE TABLE user_customer_scope (
    user_id VARCHAR(64) NOT NULL,
    person_id BIGINT NOT NULL,
    scope_status INT NOT NULL,
    PRIMARY KEY (user_id, person_id)
);

CREATE TABLE workflow_state (
    workflow_id VARCHAR(64) PRIMARY KEY,
    person_id BIGINT,
    import_batch_id BIGINT,
    created_by VARCHAR(64),
    as_of_date DATE,
    template_id VARCHAR(128),
    analysis_requirements VARCHAR(2000),
    workflow_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    error_code VARCHAR(128),
    error_message VARCHAR(4000),
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE agent_state (
    agent_state_id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    agent_type VARCHAR(32) NOT NULL,
    agent_status VARCHAR(32) NOT NULL,
    execution_id VARCHAR(128),
    retry_count INT NOT NULL,
    version BIGINT NOT NULL,
    error_code VARCHAR(128),
    error_message VARCHAR(4000),
    start_time TIMESTAMP,
    finish_time TIMESTAMP
);

CREATE TABLE agent_artifact (
    artifact_id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    agent_state_id VARCHAR(64) NOT NULL,
    agent_type VARCHAR(32) NOT NULL,
    execution_id VARCHAR(128) NOT NULL,
    compliance_result VARCHAR(32),
    result CLOB,
    storage_key VARCHAR(255),
    version INT NOT NULL,
    create_time TIMESTAMP NOT NULL
);
