-- 기본 PII 마스킹 규칙. Output Guard에서 민감 정보를 자동으로 마스킹한다.
-- H2/PostgreSQL 모두 호환되도록 UUID를 직접 지정한다.

INSERT INTO output_guard_rules (id, name, pattern, action, replacement, priority, enabled)
VALUES
    ('pii-kr-resident-id', 'KR Resident ID', '\d{6}-[1-4]\d{6}', 'MASK', '[주민번호 마스킹]', 10, true),
    ('pii-kr-phone', 'KR Phone Number', '0\d{1,2}-\d{3,4}-\d{4}', 'MASK', '[전화번호 마스킹]', 20, true),
    ('pii-email', 'Email Address', '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', 'MASK', '[이메일 마스킹]', 30, true),
    ('pii-credit-card', 'Credit Card Number', '\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}', 'MASK', '[카드번호 마스킹]', 40, true)
ON CONFLICT (id) DO NOTHING;
