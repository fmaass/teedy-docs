# Release contract — teedy-docs

Read by the `/release` skill (Step 0). Facts only; procedure lives in the skill.

## Version-literal manifest

| Surface | Location |
|---|---|
| Root pom | `pom.xml` `<version>` |
| Child pom parents | `docs-core/pom.xml`, `docs-web/pom.xml`, `docs-web-common/pom.xml` — `<parent><version>` |
| Webapp | `docs-web/src/main/webapp/package.json` (+ `package-lock.json` version fields) |
| About dialog | `docs-web/src/main/webapp/src/components/aboutHighlights.ts` `HIGHLIGHTS_VERSION` (guard-tested against root pom) |
| README | image-pin examples + "Latest stable version" line |
| db.version | `config.properties` in docs-core **and** docs-web dev + prod overlays (3 files, only when a migration ships) |

Check scripts: `scripts/check-version-consistency.sh vX.Y.Z` (tag vs poms vs package.json) and
`scripts/check-db-version.sh` (migration ↔ overlay parity). Both must exit 0.

## Required CI

Authoritative workflow: **`.github/workflows/build-deploy.yml`** ("Build and Publish").
Required jobs, each present and successful for the exact release SHA:
`test`, `test-postgres`, `test-web-postgres`, `docs-importer`, `build`, `e2e`, `docker`.
The `build` job runs the i18n parity gate (`npm run i18n:check`); `e2e` runs Playwright against the
real production image; `docker` (image publish) hard-gates on `e2e`.

## Migration mechanism

Custom incremental SQL: `docs-core/src/main/resources/db/update/dbupdate-NNN-0.sql`, applied by
`DbOpenHelper` reading `DB_VERSION` from `T_CONFIG`. Every migration bumps `db.version` in ALL THREE
config.properties overlays. Rehearse on PostgreSQL 17 against populated data (production dump or the
`test-web-postgres` job config); H2-green is not sufficient. Verify necessity against schema history
before shipping.

## Deploy target

- Publish trigger: **tags `v*` only** publish the versioned multi-arch (amd64+arm64) image to
  `ghcr.io/fmaass/teedy-docs`; `main` publishes `latest`; `release/**` is smoke-boot only, no push.
- Deploy repo: `~/projects/portainer-stacks`, pin file `stacks/teedy/docker-compose.yml`
  (pins-to-main convention — needs per-push user authorization).
- Host: Saturn (`ssh saturn.local`), service `teedy`, shared `postgres17` container.
  Git as `fabian` without sudo; docker always `sudo /usr/local/bin/docker(-compose)`.
  Roll: `git pull`, then `docker-compose pull teedy && docker-compose up -d teedy` (never `restart`).

## Acceptance probes

1. Running container digest == published manifest digest (`docker inspect` RepoDigests vs
   `docker buildx imagetools inspect`).
2. `docker exec teedy curl -s http://localhost:8080/api/app` → `current_version` == release version.
3. `docker exec postgres17 psql -U postgres -d teedy -tAc "select cfg_value_c from t_config where cfg_id_c='DB_VERSION'"` == expected level; document row count intact.
4. Representative real route: `/apidoc/` 200 with correct title, plus one authenticated API read
   exercised in-container.
5. Auth-touching releases (`/api`, `/oidc`, `/login`, Authelia): real browser login by the user —
   the surface sits behind Authelia and is not agent-verifiable (in-container probe + DB read-back
   is the agent-side acceptance).
