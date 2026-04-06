-- 멀티에이전트 오케스트레이션용 에이전트 스펙 테이블
CREATE TABLE IF NOT EXISTS agent_specs (
    id              VARCHAR(64)     PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT            NOT NULL DEFAULT '',
    tool_names      TEXT            NOT NULL DEFAULT '[]',
    keywords        TEXT            NOT NULL DEFAULT '[]',
    system_prompt   TEXT,
    mode            VARCHAR(32)     NOT NULL DEFAULT 'REACT',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_specs_name ON agent_specs (name);

COMMENT ON TABLE agent_specs IS '멀티에이전트 오케스트레이션용 서브에이전트 정의';
COMMENT ON COLUMN agent_specs.tool_names IS 'JSON 배열: 에이전트가 사용할 도구 이름 목록';
COMMENT ON COLUMN agent_specs.keywords IS 'JSON 배열: 자동 라우팅용 키워드 목록';
COMMENT ON COLUMN agent_specs.system_prompt IS '에이전트별 시스템 프롬프트 오버라이드 (null이면 기본)';
COMMENT ON COLUMN agent_specs.mode IS '실행 모드: REACT, STANDARD, PLAN_EXECUTE';
