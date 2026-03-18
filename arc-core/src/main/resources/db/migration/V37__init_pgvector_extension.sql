-- pgvector 확장 활성화
-- 주의: CREATE EXTENSION은 superuser 또는 CREATE 권한이 있는 역할이 필요합니다.
-- PostgreSQL 배포 시 DB 역할에 적절한 권한을 부여하세요:
--   GRANT CREATE ON DATABASE arcreactor TO arc;
-- 또는 superuser로 직접 실행 후 이 마이그레이션을 baseline으로 설정하세요.
CREATE EXTENSION IF NOT EXISTS vector;
