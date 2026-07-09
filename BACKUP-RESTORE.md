# Teedy — Backup & Restore Runbook (Saturn)

Production runs on Saturn: app container `teedy`, PostgreSQL in the shared
`postgres17` container (database `teedy`), encrypted file store at
`/volume1/docker/teedy/data` (mounted `/data`). Both the database AND the file
store must be backed up together — a database backup alone cannot decrypt the
files, and the files alone have no metadata.

Run remote commands with full paths + sudo (feed the password from
`/volume1/docker/.sudo_pass` via `sudo -S -p ''`).

## Back up (before any upgrade — mandatory for v3.0's destructive migrations)

    BK=/volume1/docker/backups/teedy-$(date +%Y%m%d-%H%M%S); sudo mkdir -p "$BK"
    # 1) Database (custom format)
    sudo /usr/local/bin/docker exec postgres17 pg_dump -U postgres -Fc teedy > "$BK/teedy.dump"
    # 2) File store + Lucene index (encrypted blobs)
    sudo tar czf "$BK/data.tgz" -C /volume1/docker/teedy data

## Restore-verify (into a scratch database — never overwrite prod)

    sudo /usr/local/bin/docker exec postgres17 createdb -U postgres teedy_restore_test
    sudo /usr/local/bin/docker cp "$BK/teedy.dump" postgres17:/tmp/teedy.dump
    sudo /usr/local/bin/docker exec postgres17 pg_restore -U postgres -d teedy_restore_test /tmp/teedy.dump
    # Compare row counts against prod (tables are lowercase, public schema):
    #   t_user, t_document, t_file, t_tag, t_acl, and db_version from t_config
    sudo /usr/local/bin/docker exec postgres17 psql -U postgres -d teedy_restore_test \
      -c "select count(*) from t_document;"
    sudo /usr/local/bin/docker exec postgres17 psql -U postgres -c "drop database teedy_restore_test;"

## Real restore (disaster recovery)

Stop `teedy`, drop/recreate the `teedy` database, `pg_restore` the dump into it,
restore `data.tgz` to `/volume1/docker/teedy/data`, start `teedy`. The database and
file-store snapshots MUST be from the same backup run (the file encryption key lives
in `t_user`).

## Verified drill — 2026-07-09 (pre-v3.0.0)

pg_dump (1.0 MB) + `/data` tar (241 MB, 375 entries) taken; restored into a scratch
DB; prod vs restored counts matched exactly (t_user=2, t_document=74, t_file=134,
t_tag=65, t_acl=280, db_version=36); scratch DB dropped. Backup retained at
`/volume1/docker/backups/teedy-v3-drill-20260709-034648`.
