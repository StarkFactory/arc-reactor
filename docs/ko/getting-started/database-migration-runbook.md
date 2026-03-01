# 데이터베이스 마이그레이션 런북

Flyway 검증 오류(특히 checksum mismatch)로 기동이 실패할 때 사용하는 운영 절차입니다.

## 적용 범위

- `FlywayValidateException`으로 인한 기동 실패
- 예시 오류:
  - `Migration checksum mismatch for migration version <N>`
  - `Validate failed: Migrations have failed validation`

## 발생 원인

Flyway는 `V*.sql` 파일을 변경 불가 이력으로 취급합니다. 이미 적용된 마이그레이션 파일이 수정/이름변경/삭제되면
스키마 이력 분기를 막기 위해 검증 단계에서 실패합니다.

## 즉시 대응

1. 해당 환경 배포 진행을 중단합니다.
2. 현재 정상 서비스 중인 버전은 유지합니다(재시작 루프 강제 금지).
3. 증적을 수집합니다.
  - 애플리케이션 로그
  - 배포 커밋/태그
  - 대상 DB/환경 정보

## 점검 체크리스트

1. 로그에서 mismatch 정보를 확인합니다:

```text
Migration checksum mismatch for migration version 20
-> Applied to database : <old_checksum>
-> Resolved locally    : <new_checksum>
```

2. DB 마이그레이션 이력을 조회합니다:

```sql
SELECT installed_rank, version, description, script, checksum, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

3. Git 이력에서 해당 파일 변경 여부를 확인합니다:
  - `arc-core/src/main/resources/db/migration/V<version>__*.sql`

## 표준 해결 (권장)

1. 기존 `V<version>__*.sql` 변경을 되돌려 배포 이력과 checksum을 일치시킵니다.
2. 추가 스키마 변경은 새 버전 마이그레이션으로 작성합니다.
  - 예: `V29__your_new_change.sql`
3. 재빌드/재배포합니다.

핵심은 append-only(추가만 허용) 규칙 유지입니다.

## 비상 해결(통제 필요): `flyway repair`

단기 복구가 필요하고 되돌리기 어려운 경우에만, 명시적 승인/기록 하에 사용합니다.

1. 먼저 DB 백업/스냅샷을 확보합니다.
2. 해당 환경에서 Flyway repair를 수행합니다.
3. 승인자/사유/수행자 기록을 남깁니다.
4. 후속 작업으로 append-only 규칙을 복구합니다.

예시(애플리케이션 관리형 Flyway):

```bash
# 예시입니다. 실제 운영 표준 절차/도구를 사용하세요.
./gradlew :arc-app:bootRun -Dflyway.repair=true
```

## 예방 통제

- CI 가드: `scripts/ci/check-flyway-migration-immutability.sh`
  - 기존 `V*.sql` 수정/삭제/이름변경 차단
  - 신규 마이그레이션 추가만 허용
- 릴리즈 게이트에 마이그레이션 불변성 검사 포함
- 코드리뷰 규칙: 스키마 변경은 신규 버전 마이그레이션으로만 수행

## 연관 문서

- [트러블슈팅](troubleshooting.md)
- [배포 가이드](deployment.md)
