-- =====================================================
-- V47: 런타임 설정 테이블
-- =====================================================
-- Admin이 재배포 없이 기능 토글/파라미터를 변경할 수 있도록 한다.
-- 우선순위: DB > 환경변수 > application.yml 기본값

CREATE TABLE IF NOT EXISTS runtime_settings (
    key         VARCHAR(200)    PRIMARY KEY,
    value       TEXT            NOT NULL,
    type        VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    category    VARCHAR(50)     NOT NULL DEFAULT 'general',
    description TEXT,
    updated_by  VARCHAR(100),
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_runtime_settings_category
    ON runtime_settings(category);
