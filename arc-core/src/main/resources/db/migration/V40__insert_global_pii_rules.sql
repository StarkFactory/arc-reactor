-- 국제 PII 마스킹 규칙 추가. 글로벌 서비스 대응을 위한 패턴 확장.
-- 기존 V39의 한국 PII 규칙에 추가하여 국제 PII를 탐지한다.

INSERT INTO output_guard_rules (id, name, pattern, action, replacement, priority, enabled)
VALUES
    ('pii-kr-driver-license', 'KR Driver License', '\d{2}-\d{2}-\d{6}-\d{2}', 'MASK', '[운전면허 마스킹]', 12, true),
    ('pii-kr-passport', 'KR Passport Number', '\b[A-Z]\d{8}\b', 'MASK', '[여권번호 마스킹]', 14, true),
    ('pii-us-ssn', 'US Social Security Number', '\b\d{3}-\d{2}-\d{4}\b', 'MASK', '[SSN MASKED]', 50, true),
    ('pii-jp-mynumber', 'JP My Number', '\b\d{4}\s\d{4}\s\d{4}\b', 'MASK', '[MY NUMBER MASKED]', 52, true),
    ('pii-iban', 'IBAN', '\b[A-Z]{2}\d{2}\s?[\dA-Z]{4}\s?(?:[\dA-Z]{4}\s?){1,7}[\dA-Z]{1,4}\b', 'MASK', '[IBAN MASKED]', 54, true),
    ('pii-ipv4', 'IPv4 Address', '\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b', 'MASK', '[IP MASKED]', 60, true)
ON CONFLICT (id) DO NOTHING;
