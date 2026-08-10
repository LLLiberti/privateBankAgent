ALTER TABLE workflow_state
    ADD COLUMN import_batch_id BIGINT UNSIGNED NULL AFTER person_id,
    ADD KEY idx_workflow_import_batch_id (import_batch_id);
