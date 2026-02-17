CREATE TABLE IF NOT EXISTS rag_ingestion_policy (
    id                 VARCHAR(64) PRIMARY KEY,
    enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    require_review     BOOLEAN      NOT NULL DEFAULT TRUE,
    allowed_channels   TEXT         NOT NULL DEFAULT '[]',
    min_query_chars    INT          NOT NULL DEFAULT 10,
    min_response_chars INT          NOT NULL DEFAULT 20,
    blocked_patterns   TEXT         NOT NULL DEFAULT '[]',
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rag_ingestion_candidates (
    id                   VARCHAR(36) PRIMARY KEY,
    run_id               VARCHAR(120) NOT NULL UNIQUE,
    user_id              VARCHAR(120) NOT NULL,
    session_id           VARCHAR(255),
    channel              VARCHAR(120),
    query                TEXT         NOT NULL,
    response             TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    captured_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at          TIMESTAMP,
    reviewed_by          VARCHAR(120),
    review_comment       TEXT,
    ingested_document_id VARCHAR(120)
);
