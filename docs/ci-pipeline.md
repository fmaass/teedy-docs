# CI Build & Publish Pipeline

Runbook for `.github/workflows/build-deploy.yml` ("Build and Publish") — the workflow that
tests, builds, security-scans, signs, and publishes the multi-arch Docker image to
`ghcr.io/fmaass/teedy-docs`. This document describes what the workflow **actually does** as of
`release/3.6.5`; it is the authoritative reference when the two disagree.

A separate nightly monitor, `.github/workflows/regression.yml`, rebuilds the released state and
re-runs the e2e suites. It **gates nothing** — on failure it only files/updates a
`regression-failure` tracking issue. It is not part of the publish path and is not covered in
detail here.

## 1. Job graph

`build-deploy.yml` runs on push to `main`, `release/**`, `fix/**`, tags `v*`, PRs to
`main`/`release/**`, and manual dispatch. Concurrency is serialized per ref
(`build-publish-${{ github.ref }}`, `cancel-in-progress: false`).

The candidate image is built **exactly once** (amd64 OCI archive) and every downstream test/scan
stage loads that same artifact — nothing is rebuilt from Maven per stage.

```
test ─┐
test-postgres ─┤
test-web-postgres ─┤──> build ──> candidate-image ──┬──> trivy-image ─┐
docs-importer ─┘        (uploads WAR)  (builds amd64  │                 │
                                        OCI once,     ├──> sbom ────────┤
                                        records       │                 │
                                        digest)       ├──> e2e ─────────┤
                                                      ├──> e2e-visual ──┤
                                                      │      │          │
                                                      │      └──> smoke ┤ (needs build,e2e,e2e-visual)
                                                      │                 │
                                                      └──> e2e-harness  │  (NON-GATING — not in publish needs)
                                                                        │
codeql ─────────────(source-level, no candidate)───────────────────────┤
trivy-fs ───────────(source-level, no candidate)───────────────────────┤
                                                                        │
                                                                        v
                                                       publish  (needs: smoke, codeql,
                                                                 trivy-fs, trivy-image, sbom)
```

- **`build`** needs `[test, test-postgres, test-web-postgres, docs-importer]` and uploads the
  production WAR (`docs-web.war` artifact). `build` also runs the frontend gates: version
  consistency (tags only), OpenAPI `/apidoc` parity, `npm ci`, i18n key parity
  (`npm run i18n:check`), lint (i18n no-raw-text), and Vitest unit tests.
- **`candidate-image`** needs `[build]`, downloads the WAR, builds a single `linux/amd64` image as
  an OCI archive (`type=oci`, `push: false`, `provenance: false`), records its digest, and uploads
  `candidate-image-oci` (tar + digest).
- **Consumers of the candidate** (`needs: [candidate-image]`): `trivy-image`, `sbom`, `e2e`,
  `e2e-visual`, `e2e-harness`. Each loads the **exact** archive (via `skopeo copy` or `tar -x`), so
  every gate scans/exercises the identical bytes that will ship.
- **`smoke`** needs `[build, e2e, e2e-visual]` and boots the candidate under embedded H2.
- **`codeql`** and **`trivy-fs`** are source-level jobs with no `needs` — they analyze the checked-out
  source, not the candidate image, and run in parallel from the start.
- **`e2e-harness` is intentionally NON-GATING**: it runs on every push but is **not** in the
  `publish` job's `needs`, so a red harness run does not block publish. Promotion to a gate is
  tracked in GitHub #76.
- **`publish`** needs `[smoke, codeql, trivy-fs, trivy-image, sbom]`. Because `smoke` needs
  `e2e`/`e2e-visual`, those are transitive publish gates too. `e2e-harness` is the only e2e-family
  job outside the gate set.

## 2. Publish policy (`publish` → `Resolve publish policy`, step id `cfg`)

The `publish` job **only runs at all** when:

```yaml
if: >-
  github.event_name != 'pull_request' &&
  (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v'))
```

So **PRs, `release/**`, and `fix/**` never enter a job that holds registry credentials.** Within
the job, the `cfg` step resolves per-ref behavior:

```bash
tag="${GITHUB_REF#refs/tags/}"
if [[ "${GITHUB_REF}" == refs/tags/v* && "${tag}" == *-* ]]; then
  # Pre-release tag (vX.Y.Z-rc.N / -beta.N / -alpha.N): amd64-only, GitHub pre-release, no latest.
  publish=true; platforms=linux/amd64; prerelease=true; latest=false
elif [[ "${GITHUB_REF}" == refs/tags/v* || "${GITHUB_REF}" == refs/heads/main ]]; then
  # Final release tag or main: multi-arch. `latest` only on main.
  publish=true; platforms=linux/amd64,linux/arm64; prerelease=false
  latest = (ref == refs/heads/main) ? true : false
else
  # release/**, fix/**: smoke-boot only, never published.
  publish=false; platforms=linux/amd64; prerelease=false; latest=false
fi
```

Summary:

| Ref | Publishes? | Platforms | `latest` | GitHub pre-release |
|---|---|---|---|---|
| PR (any) | No (job skipped) | — | — | — |
| `release/**`, `fix/**` | No (job skipped) | — | — | — |
| Pre-release tag `vX.Y.Z-rc.N` (any `-` suffix) | Yes | amd64 only | No | Yes |
| Final tag `vX.Y.Z` | Yes | amd64 + arm64 | No | No |
| `main` | Yes | amd64 + arm64 | **Yes** | No |

The published tag (`target` step) is `latest` on `main`, otherwise `${GITHUB_REF_NAME}` (the tag
name). The `else` (publish=false) branch is effectively dead code kept for documentation, because
the job-level `if` already excludes those refs.

## 3. Exact-digest promotion (stage → sign → verify → tag)

The candidate is built once and never rebuilt for publish. For **multi-arch** (final tag / main),
`publish` builds only the **additional** arm64 child image (the amd64 child is the candidate).
Promotion staging (`Stage exact archives and construct release digest`, step id `digests`):

1. `skopeo copy --preserve-digests` the candidate (and, multi-arch, the arm64) OCI archive to
   **run-scoped, non-release** staging tags `staging-<run_id>-<run_attempt>-{amd64,arm64,index}`.
2. Assert digest equality against the candidate:
   - **Pre-release (single-arch):** the top-level digest **is** the amd64 digest; assert
     `amd64_digest == candidate_digest`.
   - **Multi-arch:** `crane index append` the amd64+arm64 children into an index; then assert the
     index's **amd64 child** digest `== candidate_digest` and its **arm64 child** digest
     `== arm64_digest`. The top-level (`index`) digest is the manifest-list digest.
3. Build a SLSA-style provenance predicate and a CycloneDX SBOM predicate (multi-arch merges the
   amd64 + arm64 SBOM component lists).
4. **Sign + attest** the repository digest(s) with **cosign keyless** (`cosign sign`,
   `cosign attest --type cyclonedx`, `cosign attest --type slsaprovenance`) on the index digest and,
   when distinct, each child digest.
5. **Verify** every signature/attestation with identity constraints — this is the fail-closed check:

   ```
   identity=https://github.com/${GITHUB_REPOSITORY}/.github/workflows/build-deploy.yml@${GITHUB_REF}
   issuer=https://token.actions.githubusercontent.com
   cosign verify            --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" ...
   cosign verify-attestation --type cyclonedx ...
   cosign verify-attestation --type slsaprovenance ...
   ```

6. **Only then** (`Assign verified human-facing tag`) is `vX.Y.Z[-rc.N]` / `latest` assigned to the
   verified digest via `crane tag`, and the published digests are re-asserted equal to the staged
   ones. This is the first operation that exposes a human-facing tag.
7. **Staging cleanup** (`if: always()`) repoints all `staging-*` tags at one disposable empty
   manifest and deletes it; the verified release/index and child digests are never deleted.

**Concurrency / "don't move the tag backward" guard.** The workflow serializes per ref
(`concurrency.group`), but GitHub does not guarantee queue ordering, so before assigning the tag the
job re-fetches the ref and asserts the built commit is still the ref tip:

```bash
git fetch --no-tags --force origin "${GITHUB_REF}"
test "$(git rev-parse "${GITHUB_SHA}^{commit}")" = "$(git rev-parse "FETCH_HEAD^{commit}")"
```

If the ref has moved on, the tag is not assigned.

## 4. Security gates (how each fails the build)

All security gates are **fail-closed**: a missing/malformed input is an error, not a pass.

- **CodeQL** (`codeql` job): analyzes `java` + `javascript-typescript`, uploads SARIF, then a
  Python gate parses the SARIF and applies **freeze-and-ratchet** against
  `.github/baselines/codeql-known.json`. It `raise SystemExit`s (exit 1) if: the baseline schema is
  wrong; any baseline finding lacks `owner`/`expires` or has an expired `expires`; no SARIF was
  produced; a SARIF file is empty, not JSON, not SARIF `2.1.0`, or has no runs/driver/results. A
  result counts as **high** when its rule `security-severity >= 7.0`. Each high finding's identity is
  `"<ruleId>:<sha256(partialFingerprints||location)[:20]>"`; any high finding whose id is **not** in
  the baseline fails the build (exit 1).
- **Trivy fs** (`trivy-fs` job): scans the repo working tree for dependency vulns. Runs the pinned
  **aquasec/trivy Docker image by digest** (`aquasec/trivy@sha256:bcc376de…258d1c`, v0.69.3) — **not**
  the trivy GitHub Action — with `fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1`. A
  HIGH/CRITICAL finding not covered by a governed exception exits 1.
- **Trivy image** (`trivy-image` job, plus the arm64 child inside `publish`): extracts the candidate
  OCI archive to an **OCI-layout directory** and scans it (`image --input <dir> --scanners vuln
  --severity HIGH,CRITICAL --exit-code 1`) with the same pinned Trivy image.
- **Exception governance** (both Trivy jobs share this pattern). A Python step **validates** the
  baseline (`trivy-exceptions.json` for fs, `trivy-image-exceptions.json` for image) then **emits a
  plain `.trivyignore`** passed via `--ignorefile`. It exits non-zero if the schema is wrong, an
  entry is missing required fields, or an entry lacks `owner`/`expires` **or has an expired
  `expires`**. Only the **non-expired** CVE ids are written to the ignore file — so an expired or
  owner-less exception both fails the validation step **and** would no longer suppress its CVE. There
  is no blanket ignore.
- **CycloneDX SBOM** (`sbom` job): generates a CycloneDX SBOM from the candidate OCI layout
  (`--format cyclonedx --exit-code 0`), then **fail-closed validates** it: `test -s` (non-empty) and
  `jq -e '.bomFormat == "CycloneDX"'`. Uploaded as `candidate-image-sbom` and later attested.
- **cosign sign / attest / verify** (`publish` job): see §3. A verification failure (wrong
  certificate identity or OIDC issuer, missing attestation) fails `publish` before any human-facing
  tag is assigned.
- **JaCoCo** (aggregate report uploaded by the `test` job): **non-blocking**.
  `.github/baselines/jacoco.json` records `"minimums": null` and `"policy": "report-only; thresholds
  are intentionally deferred until after rc.2"`. No coverage threshold currently fails the build.

## 5. Maintaining the gates (runbook)

**(a) When a gate goes red on a REAL finding.** Fix it — bump the dependency or fix the code — or,
only if the finding is genuinely accepted for now, add a **governed exception** with a real `owner`
and a **near-term `expires`**. Never add a blanket ignore and never disable a gate. An exception is
self-expiring: once `expires` passes, the validation step fails the build until the finding is
re-triaged.

**(b) Exception / baseline entry schema.** Every entry in all three baseline files carries the same
required fields (`id, finding, reason, owner, introduced, expires, compensating_control,
removal_issue`). Real examples:

Trivy image exception (`.github/baselines/trivy-image-exceptions.json`):

```json
{
  "id": "CVE-2026-1605",
  "finding": "jetty-server",
  "reason": "Pre-existing dependency CVE surfaced at image-scan adoption (rc.2 Phase F); fix tracked in #105",
  "owner": "fmaass",
  "introduced": "2026-07-15",
  "expires": "2026-10-15",
  "compensating_control": "#105",
  "removal_issue": "#105"
}
```

CodeQL baseline finding (`.github/baselines/codeql-known.json`):

```json
{
  "id": "java/path-injection:1d4901c720ecdb38a312",
  "finding": "Pre-existing CodeQL java/path-injection finding",
  "reason": "Pre-existing at CodeQL gate adoption (rc.2 Phase F); triage tracked in #104",
  "owner": "fmaass",
  "introduced": "2026-07-15",
  "expires": "2026-10-15",
  "compensating_control": "Freeze-and-ratchet CI gate rejects every unlisted high-severity finding ID.",
  "removal_issue": "#104"
}
```

**(c) Currently baselined findings and their remediation.** These are pre-existing findings adopted
when the gates went in (rc.2 Phase F), frozen for remediation, **not** ignored forever. All three
baselines expire **2026-10-15**, which forces re-triage:

- **SAST (CodeQL):** 23 findings in `codeql-known.json`, tracked in **GitHub #104** (mostly
  `java/path-injection`, plus command-line-injection, regex-injection, polynomial-redos,
  implicit-cast).
- **Dependency CVEs (Trivy image):** 8 exceptions in `trivy-image-exceptions.json`, tracked in
  **GitHub #105** (mina-core, jackson-databind, several jetty modules, itext).
- **Trivy fs:** `trivy-exceptions.json` currently has **zero** exceptions.

**(d) Adding a new CodeQL baseline id.** The gate computes the id as
`"<ruleId>:<fingerprint>"`, where `<fingerprint>` is the first 20 hex chars of the SHA-256 of the
result's `partialFingerprints` (JSON, `sort_keys=True`), or of the first location's
`physicalLocation` when no `partialFingerprints` exist. Read the exact id from the failing gate log
line (`NEW HIGH CODEQL FINDING <id> (<score>): <message>`) and add a full entry (all eight fields,
real owner, near `expires`, `removal_issue`) to `codeql-known.json`. Adding an id is accepting a
finding — prefer fixing it.

**(e) Fix, don't baseline, when you can.** Example: **CVE-2025-66021** in
`owasp-java-html-sanitizer` was **fixed by bumping the dependency** (to `20260101.1`, commit
`65514e69`), **not** added to a baseline. It appears in no exception file. That is the preferred
path — a governed exception is the fallback for findings that cannot be fixed immediately.
