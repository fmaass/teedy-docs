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
`publish`. The `build` job runs, in order: the checker self-tests
(`run-checker-self-tests.sh`), version consistency (`check-version-consistency.sh`),
**OpenAPI spec parity** (`check-openapi-parity.mjs`), i18n key parity (`npm run i18n:check`),
frontend lint, the e2e typecheck (`npm run typecheck:e2e`), frontend unit tests, then the
`-Pprod` Maven build; `e2e`/`e2e-visual` run
Playwright against the single candidate image; `smoke` boots that image; `codeql`/`trivy-fs`/
`trivy-image`/`sbom` are the security gates. The `publish` job (tag/main only) needs
`smoke` + every security gate and promotes the exact signed+verified candidate digest to the release
tag. `e2e-harness` runs but is **non-gating** (#76). Full pipeline runbook: **`docs/ci-pipeline.md`**.

## Mirror gates and the pre-push guardrail

Six gates compare a hand-maintained mirror against its source of truth — five that always run and
one that needs a tag to compare against. None of them can be failed by a unit test:

| Mirror | Source of truth | Gate | Also run by CI |
|---|---|---|---|
| `apidoc/openapi.json` | JAX-RS resource annotations | `scripts/check-openapi-parity.mjs` | yes — `build` job |
| locale JSONs | `en.json` key set | `npm run i18n:check` | yes — `build` job |
| `db.version` (3 overlays) | newest `dbupdate-NNN` migration | `scripts/check-db-version.sh` | yes — `test` job |
| `codeql-known.json` coordinates | the triaged sink lines | `scripts/check-codeql-baseline-drift.mjs` | **no — pre-push only** |
| `Dockerfile` `ENV JETTY_VERSION` | pom `<org.eclipse.jetty.version>` | `scripts/check-jetty-version.sh` | **no — pre-push only** |
| poms + `package.json` | the release tag | `scripts/check-version-consistency.sh vX.Y.Z` | yes — `build` job, tag pushes only |

Each of these checkers has a fixture self-test (`scripts/test-check-*.sh`) that plants a defect and
asserts the checker still fires. The `build` job runs all of them through
`scripts/run-checker-self-tests.sh`, which discovers them by glob — a self-test added later joins
that gate by existing, and an empty glob is a failure, not a pass.

The two pre-push-only gates have no CI backstop at all, which is the point: the CodeQL baseline
keyed to line coordinates and the two independent Jetty pins (the pom property governs only the
embedded dev server; production downloads jetty-home from the Dockerfile's own version and checksum)
both drift silently, and a Jetty CVE fix applied to the pom alone would leave the shipped image
inert. Nothing else catches either one.

For the four gates CI does run, drift still stays invisible to local verification until the build
fails — and on a tag push that is a failed release build on an already-public tag. v3.6.7 lost its
first tag exactly this way: the #139 audit-log query params and the #147 `dark_mode` form param were
added to the resources but never mirrored into `openapi.json`, so a fully green local run
(backend suite, frontend unit, lint, i18n) still produced a red release build.

Run all six at once with **`scripts/check-release-mirrors.sh [vX.Y.Z]`** (the tag argument adds
the version-consistency gate; without it that one gate is skipped and the other five still run).
**`.githooks/pre-push`** runs the wrapper automatically on every push and passes the tag name when a
`v*` tag is pushed — so the pre-push hook, not CI, is the only place the full set runs together.
Enable the hook once per clone — `scripts/dev_setup.sh` does it, or
`git config core.hooksPath .githooks`. Deliberate override: `SKIP_RELEASE_MIRRORS=1`.

Before tagging, run the issue-close-comment gate: every issue closed by this release must already
carry its close comment.

```
scripts/check-issue-close-comments.sh <prev-release-tag>
```

## Pre-tag regression (standing rule)

Every rc/version closeout runs, in addition to CI: the Playwright suites AND the
browser-harness regression (`scripts/e2e-browser-harness.sh`, via `scripts/e2e-harness-run.sh`)
against a locally-running build. The script is one suite — the CI `e2e-harness` job runs the same
checks but is non-gating and does not satisfy this rule; what this rule adds is a LOCAL, gating,
evidence-recorded run.
Record the harness scenario counts and the script's exit code in the release evidence before
requesting the tag go.

## Dependency currency (informational, non-gating)

Before cutting a release, take a reading of how far the dependency pins have drifted:

```
cd docs-web/src/main/webapp && npm outdated
mvn versions:display-dependency-updates          # ad hoc — deliberately NOT wired into the POM
```

Neither command gates the release; a non-empty `npm outdated` is expected and normal (it exits
non-zero whenever anything is listed, so it must never be used as a pass/fail gate). Record the
resulting delta as a table in the release record page — package, current, latest, and the
disposition (bumped this release, or deferred with the reason). The value is the written disposition: an unbumped major stays a deliberate, dated
decision rather than silent drift, and the next release starts from a known baseline.

Majors are out of scope for a routine pre-release refresh — take patch/minor lines only, and file
an issue for any major worth its own migration.

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
   configuration. See issue #154.
7. Authenticated login (OIDC/MFA) needs the operator's own credentials, so a real-browser login plus a
   state-changing action — confirming CSRF enforcement — remains the operator's acceptance step.
   Note: deploying across the credential-epoch (migration 055) forces a one-time global re-login.
