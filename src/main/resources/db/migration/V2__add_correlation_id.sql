-- The caller's own trace id (e.g. an AgentForge retrieval or execution id), so one caller-side
-- operation can be followed into every gateway attempt it caused, and back.
ALTER TABLE gw_call_attempt ADD COLUMN correlation_id VARCHAR(128) NULL;

CREATE INDEX idx_gw_attempt_correlation ON gw_call_attempt (correlation_id);
