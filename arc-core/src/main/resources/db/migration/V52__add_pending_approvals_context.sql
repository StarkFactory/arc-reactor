-- R221 Directive #1 Tool Approval 4단계 정보 구조화.
-- docs/agent-work-directive.md §3.1 Tool Approval UX 강화 원칙에 따라
-- pending_approvals 테이블에 context_json 컬럼을 추가한다.
--
-- 컬럼 값은 ApprovalContext 데이터 클래스의 JSON 직렬화:
--   { "reason": "...", "action": "...", "impactScope": "...", "reversibility": "..." }
--
-- NULL 허용: 기존 row (V22 당시 생성된) 와 호환성을 위해 nullable.
-- ApprovalContextResolver 빈을 등록한 경우에만 실제 값이 기록된다 (opt-in).

ALTER TABLE pending_approvals
    ADD COLUMN IF NOT EXISTS context_json TEXT;
