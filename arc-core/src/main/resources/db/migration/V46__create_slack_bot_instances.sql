-- =====================================================
-- V46: Slack 봇 인스턴스 테이블 (멀티 봇 페르소나)
-- =====================================================
-- 1개의 Arc Reactor에 여러 Slack 봇을 연결하여
-- 각각 다른 페르소나(전문 분야)로 동작하는 구조를 지원한다.

CREATE TABLE IF NOT EXISTS slack_bot_instances (
    id              VARCHAR(36)     PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL UNIQUE,
    bot_token       VARCHAR(500)    NOT NULL,
    app_token       VARCHAR(500)    NOT NULL,
    persona_id      VARCHAR(36)     NOT NULL,
    default_channel VARCHAR(100),
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_slack_bot_instances_enabled
    ON slack_bot_instances(enabled);
