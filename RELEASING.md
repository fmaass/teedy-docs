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
`test`, `test-postgres`, `test-web-postgres`, `docs-importer`, `build`, `codeql`, `trivy-fs`,
`candidate-image`, `trivy-image`, `sbom`, `e2e`, `e2e-visual`, `smoke`, and — on a publishing ref —
`publish`. The `build` job runs, in order: version consistency (`check-version-consistency.sh`),
**OpenAPI spec parity** (`check-openapi-parity.mjs`), i18n key parity (`npm run i18n:check`),
frontend lint, frontend unit tests, then the `-Pprod` Maven build; `e2e`/`e2e-visual` run
Playwright against the single candidate image; `smoke` boots that image; `codeql`/`trivy-fs`/
`trivy-image`/`sbom` are the security gates. The `publish` job (tag/main only) needs
`smoke` + every security gate and promotes the exact signed+verified candidate digest to the release
tag. `e2e-harness` runs but is **non-gating** (#76). Full pipeline runbook: **`docs/ci-pipeline.md`**.

## Mirror gates and the pre-push guardrail

Four gates compare a hand-maintained mirror against its source of truth. None of them can be
failed by a unit test, and all four run only in the CI `build` job:

| Mirror | Source of truth | Gate |
|---|---|---|
| `apidoc/openapi.json` | JAX-RS resource annotations | `scripts/check-openapi-parity.mjs` |
| locale JSONs | `en.json` key set | `npm run i18n:check` |
| `db.version` (3 overlays) | newest `dbupdate-NNN` migration | `scripts/check-db-version.sh` |
| poms + `package.json` | the release tag | `scripts/check-version-consistency.sh vX.Y.Z` |

Because they are push-only, drift stays invisible to local verification until the build fails —
and on a tag push that is a failed release build on an already-public tag. v3.6.7 lost its first
tag exactly this way: the #139 audit-log query params and the #147 `dark_mode` form param were
added to the resources but never mirrored into `openapi.json`, so a fully green local run
(backend suite, frontend unit, lint, i18n) still produced a red release build.

Run all four at once with **`scripts/check-release-mirrors.sh [vX.Y.Z]`** (the tag argument adds
the version-consistency gate). **`.githooks/pre-push`** runs it automatically on every push and
passes the tag name when a `v*` tag is pushed. Enable the hook once per clone — `scripts/dev_setup.sh`
does it, or `git config core.hooksPath .githooks`. Deliberate override: `SKIP_RELEASE_MIRRORS=1`.

## Pre-tag regression (standing rule)

Every rc/version closeout runs, in addition to CI: the Playwright suites AND the **full**
browser-harness regression (`scripts/e2e-browser-harness.sh`) against a locally-running build.
The CI `e2e-harness` job is a trimmed smoke and is non-gating — it does not satisfy this rule.
Record the harness scenario counts and the script's exit code in the release evidence before
requesting the tag go.

## Migration mechanism

Custom incremental SQL: `docs-core/src/main/resources/db/update/dbupdate-NNN-0.sql`, applied by
`DbOpenHelper` reading `DB_VERSION` from `T_CONFIG`. Every migration bumps `db.version` in ALL THREE
config.properties overlays. Rehearse on PostgreSQL 17 against populated data (production dump or the
`test-web-postgres` job config); H2-green is not sufficient. Verify necessity against schema history
before shipping.

## Deploy target

- Publish trigger: **tags `v*` only** publish the versioned multi-arch (amd64+arm64) image to
  `ghcr.io/fmaass/teedy-docs`; `main` publishes `latest`; `release/**` is smoke-boot only, no push.
- **Host, stack path, hostnames and addresses are deployment-specific and deliberately not recorded
  here** — this file is public. Operators keep them in their own private notes; for this fork's
  maintainer they live in the untracked project context file.
- Deploy stack: a compose file in the operator's stack repo, rolled with `docker compose up -d <service>`
  in the stack directory — **never `restart`**, which reuses the old image.
- Image: the compose pins a local tag (`teedy-docs:local`) rather than pulling from ghcr directly.
  Deploy a new version by retagging the published CI image:
  `docker pull ghcr.io/fmaass/teedy-docs:vX.Y.Z && docker tag ghcr.io/fmaass/teedy-docs:vX.Y.Z teedy-docs:local`,
  then `up -d --force-recreate`.
- Set `DOCS_CSRF_ENFORCE=true` in the stack env (enabled since v3.6.6).
- The database may live on a different host from the application; a release that ships a migration must
  confirm which instance `DATABASE_URL` actually points at before taking the pre-deploy backup.

## Acceptance probes

1. Running container digest == published manifest digest (`docker inspect` RepoDigests vs
   `docker buildx imagetools inspect`).
2. `docker exec teedy curl -s http://localhost:8080/api/app` → `current_version` == release version.
3. `docker exec postgres17 psql -U postgres -d teedy -tAc "select cfg_value_c from t_config where cfg_id_c='DB_VERSION'"` == expected level; document row count intact.
4. Representative real route: `/apidoc/` 200 with correct title, plus one authenticated API read
   exercised in-container.
5. **Verify the served `current_version` through the real proxy, not just inside the container.** An
   in-container probe passes even when the deploy landed on the wrong host or instance — exactly how a
   release once reported a stale version at the public URL while looking correct locally. Where the
   deployment host cannot reach its own proxy address (a common macvlan limitation), resolve the
   hostname to the proxy's container-network address for the probe rather than skipping it.
6. **The browser-harness must never be pointed at a live deployment** — it requires default
   `admin/admin` credentials and seeds documents including a hostile XSS payload. Run it against a
   disposable instance built from the same published image, ideally behind the same proxy
   configuration. See issue #151.
7. Authenticated login (OIDC/MFA) needs the operator's own credentials, so a real-browser login plus a
   state-changing action — confirming CSRF enforcement — remains the operator's acceptance step.
   Note: deploying across the credential-epoch (migration 055) forces a one-time global re-login.
