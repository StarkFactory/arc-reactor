-- Gemini 모델의 provider를 'gemini' → 'google'로 수정.
-- Spring AI Google GenAI 모듈이 provider를 'google'로 보고하므로
-- 가격 매칭이 안 되어 비용이 0으로 기록되던 문제 수정.
UPDATE model_pricing SET provider = 'google' WHERE provider = 'gemini';
