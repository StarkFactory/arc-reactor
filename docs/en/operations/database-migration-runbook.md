# Database Migration Runbook

Use this runbook when startup fails with Flyway migration validation errors such as checksum
mismatch on an already applied versioned migration.

## Scope

- Runtime startup failures involving `FlywayValidateException`
- Errors like:
  - `Migration checksum mismatch for migration version <N>`
  - `Validate failed: Migrations have failed validation`

## Why this happens

Flyway treats `V*.sql` files as immutable history. If an already applied migration file is edited,
renamed, or deleted, Flyway validation fails to prevent divergent schema history.

## Immediate response

1. Stop rollout for the affected environment.
2. Keep current serving version running (do not force restart loops).
3. Capture evidence:
  - application logs
  - current git commit/tag
  - database name and environment

## Investigation checklist

1. Confirm mismatch details from logs:

```text
Migration checksum mismatch for migration version 20
-> Applied to database : <old_checksum>
-> Resolved locally    : <new_checksum>
```

2. Query migration history:

```sql
SELECT installed_rank, version, description, script, checksum, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

3. Compare the migration file in git history:
  - `arc-core/src/main/resources/db/migration/V<version>__*.sql`
  - identify who changed it and when

## Standard fix (preferred)

1. Revert changes to the existing `V<version>__*.sql` file so checksums match deployed history.
2. Create a new migration version for additional schema changes.
  - Example: `V29__your_new_change.sql`
3. Rebuild and redeploy.

This preserves append-only migration history.

## Emergency fix (controlled): `flyway repair`

Use only with explicit approval and change record when revert is impossible in the short term.

1. Take a DB backup/snapshot first.
2. Run Flyway repair in that environment.
3. Record who approved and why.
4. Open follow-up to restore append-only discipline.

Example (application-managed Flyway):

```bash
# Example only. Use your approved operational path/tooling.
./gradlew :arc-app:bootRun -Dflyway.repair=true
```

If your platform runs Flyway separately, use the platform standard command instead.

## Preventive controls

- CI guard: `scripts/ci/check-flyway-migration-immutability.sh`
  - blocks modification/deletion/rename of existing `V*.sql`
  - allows only additive new migration files
- Release gate: include migration immutability check before tagging
- Code review rule: schema changes must be additive migrations only

## Related docs

- [Release Readiness Checklist](../releases/release-readiness-checklist.md)
- [Troubleshooting](../getting-started/troubleshooting.md)
