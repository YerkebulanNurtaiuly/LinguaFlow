# Backups and restore (OPS-05)

How LinguaFlow's data is backed up, how to get it back, and the record of the drills that
prove it works. Everything a learner has is in the PostgreSQL database; the services keep no
other state (rate-limit buckets and the circuit breaker live in memory and rebuild
themselves), so restoring the database restores the app.

## Targets

| | Target | How |
|---|---|---|
| Data loss (RPO) | a few minutes at most | Render point-in-time recovery (PITR) |
| Time to recover (RTO) | under 1 hour | a new database from PITR, then a backend redeploy |
| Long-term copies | monthly, kept 12 months | a logical backup stored encrypted outside Render |

## What protects the data

1. **Point-in-time recovery (Render).** Paid Render databases keep a continuous log:
   the last 3 days on a Hobby workspace, 7 days on Pro or higher. Recovery makes a **new**
   database at any moment in that window; the damaged one stays as it is.
2. **Logical exports (Render).** The database's **Recovery** page can create a `pg_dump`
   export on demand, kept by Render for 7 days.
3. **Our own logical backups.** `scripts/db-backup.sh` writes a `pg_dump` custom-format
   archive, which `scripts/db-restore.sh` restores. Use them before risky operations
   (a migration that rewrites data, a bulk fix) and for the monthly long-term copy.

Backups hold every learner's personal data (SEC-02, `docs/security/privacy.md`):
- never commit one (`*.dump`, `*.backup` and `*.sql.gz` are git-ignored), never put one in
  a chat, ticket or shared drive;
- encrypt a copy before it leaves your machine, e.g. `age -r <recipient> -o x.dump.age x.dump`
  or `gpg --symmetric`; keep the key apart from the backup;
- delete copies past their retention. An account deleted since a backup was taken would
  come back with a restore; after a restore, delete such accounts again (keep a list of
  erasures made since the backup's time).

## The scripts

All three take database URLs from environment variables, never arguments, so passwords stay
out of shell history and process lists. They use the local `pg_dump`/`psql` when installed,
otherwise the `postgres:16-alpine` image (`PG_IMAGE`) on the Docker network `PG_NETWORK`
(default `host`).

```bash
# Back up (default file: backups/linguaflow-<UTC time>.dump, readable only by you)
SOURCE_DATABASE_URL='postgresql://user:pass@host:5432/linguaflow' scripts/db-backup.sh

# Restore into an EMPTY database; it refuses a database that already has tables
TARGET_DATABASE_URL='postgresql://user:pass@host:5432/linguaflow' scripts/db-restore.sh backups/x.dump

# Compare every table's row count between two databases (exit 1 on a difference)
SOURCE_DATABASE_URL=... TARGET_DATABASE_URL=... scripts/db-verify.sh
```

- **Backup:** `pg_dump --format=custom --no-owner --no-acl`, checked with
  `pg_restore --list` before it's kept; prints size, table count and SHA-256.
- **Restore:** `pg_restore --exit-on-error --single-transaction`, so a failed restore leaves
  the target empty. It prints the Flyway version of the restored schema.
- **Owners:** left out on purpose: the restore target has its own user, and the `pg_trgm`
  extension (V2) is created by that user (it's a trusted extension).
- **Render URLs:** use the database's External Database URL and add `?sslmode=require` if it
  isn't there. The database accepts no outside connections, so add your IP under
  **Access Control** for the session and remove it afterwards; never `0.0.0.0/0`.

## Recovering production

Decide first what happened:

- **Bad data from a bug or a mistake** (rows deleted or overwritten): recover to the moment
  just before it.
- **Lost or corrupt database:** recover to the latest moment.
- **A bad migration:** roll the app back first (`docs/operations/deployment.md`), then
  recover to just before the deploy if the migration changed data.

Steps, with the times from the drills as a guide:

1. **Stop writes (1 min).** Suspend `linguaflow-api-production` in the Render Dashboard, so
   nothing writes to the damaged database while you work. The site shows its error page.
2. **Note the moment (2 min).** From the logs (ECS JSON, `docs/operations/observability.md`)
   or the deploy history, find the UTC time to recover to.
3. **Recover (10–30 min, by size).** Database → **Recovery** → **Point-in-time recovery**,
   choose the time, and name the new database, e.g. `linguaflow-db-production-2026-11-16`.
   Wait until it is available.
4. **Check the copy (5 min).** Allow your IP on the new database for the session, then
   compare it with what you expect, e.g. the latest rows before the incident:
   `select max(created_at) from vocabulary_items;` and
   `select version, success from flyway_schema_history order by installed_rank desc limit 3;`.
   `scripts/db-verify.sh` compares two databases table by table when you have a reference.
5. **Point the backend at it (5 min).** In `render.yaml`, change the production backend's
   `DATABASE_URL` `fromDatabase.name` to the new database and add the new database to the
   production environment's `databases` (so a Blueprint sync doesn't switch back); or, in an
   emergency, set `DATABASE_URL` in the Dashboard to the new database's Internal Database URL
   and update `render.yaml` right after. Resume the backend; Flyway checks the schema at
   startup.
6. **Check the app (5 min).** `scripts/check-deployment.sh <production-url> <commit>`, then
   sign in and open the dashboard, vocabulary and a review session.
7. **Clean up.** Re-apply account erasures made after the recovery point. Keep the old
   database for a few days in case something was missed, then delete it. Write down what
   happened and the times in the drill log below.

Restoring one of our own backups instead of PITR is the same, with step 3 replaced by
creating an empty database (New → PostgreSQL) and running `scripts/db-restore.sh` into it.

## Drills

`scripts/restore-drill.sh` is the automated drill. On a throwaway Docker stack it:
starts PostgreSQL and the backend with the demo data, adds a marker word through the API,
stops the backend and backs the database up, restores the backup into a **separate, empty
PostgreSQL server**, compares every table, starts a backend on the restored copy and checks
through the API that the demo learner signs in and has every word, the marker included. It
prints the time of each step and removes everything afterwards (`KEEP_DRILL=1` keeps it).

The **Restore drill** workflow (`.github/workflows/restore-drill.yml`) runs it on the 1st of
every month, on demand, and whenever the backup scripts change, and puts the step times in
the run summary. A failed run means restores are broken: fix that before anything else.

Once a quarter, and before launch, also drill against staging on Render: back staging up
with `db-backup.sh`, run a PITR recovery of `linguaflow-db-staging` to a new database, check
it with `db-verify.sh` against the backup restored locally, and delete the new database.
Record it below.

### Drill log

| Date | Where | What | Result |
|---|---|---|---|
| 2026-11-16 | local Docker, `scripts/restore-drill.sh` | backup of the demo database plus a marker word (54 words, 33 tables, 469 rows; 113 kB archive, 2 s), restore into a new PostgreSQL 16 server (1 s, Flyway version 8), table comparison, backend on the restored copy | Passed: 0 differences, the learner signed in with all 54 words and the marker; an empty server to a healthy app in 11 s |
| — | Render staging, PITR | not done yet | to do before launch |
