-- 어드민 세션 태그 테이블
CREATE TABLE IF NOT EXISTS session_tags (
    id          VARCHAR(36)   PRIMARY KEY,
    session_id  VARCHAR(255)  NOT NULL,
    label       VARCHAR(100)  NOT NULL,
    comment     TEXT,
    created_by  VARCHAR(255)  NOT NULL,
    created_at  BIGINT        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_tags_session_id ON session_tags (session_id);
