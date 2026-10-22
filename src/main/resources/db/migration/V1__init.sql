-- Portable DDL: runs on MySQL 8 in production and on H2 (MySQL mode) in tests.

CREATE TABLE gw_client_key (
    id              VARCHAR(40)  NOT NULL PRIMARY KEY,
    key_hash        CHAR(64)     NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    team            VARCHAR(64)  NOT NULL,
    project         VARCHAR(64)  NOT NULL,
    allowed_routes  VARCHAR(256) NOT NULL,
    allow_overseas  BOOLEAN      NOT NULL DEFAULT FALSE,
    rpm             INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_gw_client_key_hash UNIQUE (key_hash)
);

CREATE TABLE gw_provider_key (
    id                  VARCHAR(40)   NOT NULL PRIMARY KEY,
    provider            VARCHAR(32)   NOT NULL,
    secret_ciphertext   VARCHAR(2048) NOT NULL,
    master_key_version  VARCHAR(16)   NOT NULL,
    fingerprint         VARCHAR(16)   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    disabled_at         TIMESTAMP(6)  NULL
);

CREATE INDEX idx_gw_provider_key_provider ON gw_provider_key (provider, status);

-- One row per upstream attempt, failed ones included. Written synchronously before the
-- response is returned: an asynchronous queue would lose billing rows on a crash.
CREATE TABLE gw_call_attempt (
    id                        BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id                VARCHAR(40)   NOT NULL,
    attempt_no                INT           NOT NULL,
    client_key_id             VARCHAR(40)   NOT NULL,
    team                      VARCHAR(64)   NOT NULL,
    project                   VARCHAR(64)   NOT NULL,
    route                     VARCHAR(32)   NOT NULL,
    provider                  VARCHAR(32)   NOT NULL,
    model                     VARCHAR(128)  NOT NULL,
    region                    VARCHAR(16)   NOT NULL,
    served_model              VARCHAR(128)  NULL,
    model_check               VARCHAR(24)   NULL,
    provider_key_fingerprint  VARCHAR(16)   NULL,
    outcome                   VARCHAR(16)   NOT NULL,
    failure_kind              VARCHAR(32)   NULL,
    http_status               INT           NULL,
    input_tokens              INT           NOT NULL DEFAULT 0,
    cached_input_tokens       INT           NOT NULL DEFAULT 0,
    cache_write_tokens        INT           NOT NULL DEFAULT 0,
    output_tokens             INT           NOT NULL DEFAULT 0,
    reasoning_tokens          INT           NOT NULL DEFAULT 0,
    cost                      DECIMAL(20,8) NOT NULL DEFAULT 0,
    cost_estimated            BOOLEAN       NOT NULL DEFAULT FALSE,
    currency                  CHAR(3)       NOT NULL,
    request_hash              CHAR(64)      NOT NULL,
    latency_ms                BIGINT        NOT NULL,
    usage_date                DATE          NOT NULL,
    created_at                TIMESTAMP(6)  NOT NULL
);

CREATE INDEX idx_gw_attempt_request ON gw_call_attempt (request_id);
CREATE INDEX idx_gw_attempt_team_date ON gw_call_attempt (team, usage_date);
CREATE INDEX idx_gw_attempt_provider_date ON gw_call_attempt (provider, usage_date, model);
