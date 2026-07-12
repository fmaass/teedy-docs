# Build-Time OpenAPI Generation Spike (v3.5)

Status: evaluation complete. Time-boxed bounded spike, no production behavior change.
Served spec `docs-web/src/main/webapp/public/apidoc/openapi.json` remains authoritative and
byte-identical (verified: `git diff` on that file is empty).

## Question

Could build-time OpenAPI generation from `io.swagger.core.v3` jakarta annotations replace the
hand-authored `openapi.json` in v3.6 — without a runtime scanner in the WAR, and without losing
fidelity of the current hand-curated spec?

## Method

- Target resource: `TagResource` (8 operations). Annotated ONLY this resource with
  `@Operation`, `@Parameter`, `@ApiResponse`, `@Schema`, carrying the existing hand-written
  `@apiXxx` / javadoc descriptions verbatim as annotation prose. Behavior unchanged.
- Response/request shapes modeled as documentation-only nested `@Schema` DTOs (`TagDetail`,
  `TagWriteForm`, `TagListResult`, …) mirroring the JSON the resource actually builds via
  `Json.createObjectBuilder`. This deliberately measures whether annotations can reproduce
  response schemas the hand slice omits.
- Dependency: `swagger-annotations-jakarta:2.2.30`, scope **provided** (compile-visible, not
  packaged). Plugin: `swagger-maven-plugin-jakarta:2.2.30`, `resolve` goal bound to
  `process-classes`, `resourceClasses` restricted to `TagResource`, output to
  `target/generated-openapi/openapi.json` (never under `src/` or `public/`).
- Gates run foreground with an isolated Maven repo (`.m2repo`), JDK 21.

## Gate results

| Gate | Command | Result |
|------|---------|--------|
| 4a build | `mvnw -pl docs-web -am -DskipTests package` | **BUILD SUCCESS**; `swagger:2.2.30:resolve` executed at process-classes, wrote `target/generated-openapi/openapi.json` (14861 bytes). |
| 4b WAR | `unzip -l *.war \| grep WEB-INF/lib..swagger` | **empty (PASS)** — no swagger jar packaged. |
| 4b lib diff | before/after `WEB-INF/lib/*.jar` lists | **identical: 137 jars → 137 jars, `diff` exit 0.** Zero new jars; `provided` scope held. |
| 4c parity | `node scripts/check-openapi-parity.mjs` | **green before AND after** — 124 endpoints, 280 visible parameters. Served `openapi.json` git-diff = 0 lines. |
| 4d tests | `mvnw -pl docs-web -am clean test` | **BUILD SUCCESS, exit 0** — docs-core 138, docs-web-common 16, docs-web 251; 0 failures, 0 errors (exact baseline, no regression). |

The `provided`-scope decision is the load-bearing one: with `optional` the annotation jar would
still package into a WAR-packaged project; `provided` is what keeps `WEB-INF/lib` byte-identical.

## Structured diff: generated vs hand-authored `/tag` slice

Both cover all **8** operations (list, get, create, update, delete, stats, facets, co-occurrence)
with identical HTTP verbs and paths. Where they differ:

| Dimension | Hand-authored (served) | Generated (this spike) | Assessment |
|-----------|------------------------|------------------------|------------|
| `openapi` version | `3.0.3` | `3.0.1` | Plugin pins 3.0.1; cosmetic drift, both valid. |
| `operationId` | `get_tag_id`, `put_tag`, … (verb_path convention) | bare Java method name (`get`, `add`, `update`, …) | **Regression** — generated ids collide semantically (`get` used by multiple resources across a full migration) and break the parity script's id expectations. Fixable only by hand-writing `operationId=` on every `@Operation`. |
| Summaries | present, curated | reproduced verbatim | Parity. |
| Descriptions | absent (slice has none) | present (from javadoc) | **Win** — richer than served. |
| Path param `id` | one entry | **duplicated** (annotation + `@PathParam` signature both emitted) | **Defect** — double-declaration produces two `id` params. Must annotate XOR rely on signature, not both. |
| `app_key` query param | absent | **present on every op** | Inherited `@QueryParam("app_key")` from `BaseResource` (BaseResource.java:61). Generator is arguably *more* correct, but it diverges from the served spec on all 124 endpoints and would need suppression to match. |
| Form request body | `application/x-www-form-urlencoded`, inline object | `application/x-www-form-urlencoded`, `$ref` to component | **Parity + win** — correct media type auto-inferred; schema promoted to a reusable component. |
| Response schemas | `200: {description: Success}` only (openapi.json ~L2814) | full typed schemas w/ `$ref`, per-property descriptions, enums (`perm`, `type`) | **Win** — but only because this spike hand-wrote `@Schema` DTOs; the resource has no typed return model, so this fidelity is manual effort, not free. |
| Error responses (403/404/400) | absent | present, with the `@apiError` prose folded into the description | **Win** — carried from the javadoc error catalog. |
| Security schemes | `cookieAuth` + `apiKeyAuth`, top-level `security` (openapi.json ~L3842) | **none** | Known asymmetry — a resource-only generation has no document-level input, so global security is unreproducible without an `@OpenAPIDefinition`/`@SecurityScheme` seed class. |
| `info` block (title/version/description) | populated | `{}` empty | Same root cause — needs a document-level `@OpenAPIDefinition` seed. |
| `servers` | n/a | none | n/a. |

### Fidelity findings (honest, not failures)

1. **Response-schema fidelity is achievable but not automatic.** The resources return
   `Json.createObjectBuilder` blobs, not typed POJOs. Every response/request schema in this spike
   is a hand-written `@Schema` DTO. A migration inherits ~all of the current documentation effort;
   annotations relocate it into Java, they do not eliminate it.
2. **operationId regression is real.** The parity script and any client-generator key off stable,
   unique operationIds. Generated ids are bare method names — non-unique across resources. Every
   `@Operation` needs an explicit `operationId=`.
3. **Duplicate path param** from annotation-plus-signature double declaration — a per-parameter
   footgun that recurs on every path-param method.
4. **Inherited `app_key`** surfaces globally; matching the served spec requires suppressing it
   (`@Parameter(hidden=true)` on the BaseResource field, or post-processing).
5. **Document-level metadata** (`info`, `security`, `servers`, the two global security schemes)
   is NOT reproducible from a resource-only scan. A migration needs one `@OpenAPIDefinition` +
   `@SecurityScheme` seed class to restore the top matter the hand spec provides today.

## Effort estimate — full 20-resource migration

- Per resource: annotate operations + author `@Schema` DTOs for every response/request shape.
  TagResource (8 ops, 11 schemas) took the bulk of a spike session. Resources vary 3–15 ops.
- One-time: `@OpenAPIDefinition` seed (info + servers + `cookieAuth`/`apiKeyAuth`), a global
  `app_key`/hidden-param convention, explicit `operationId` on ~124 operations, and reworking or
  retiring `check-openapi-parity.mjs` to validate the generated artifact instead of the static file.
- Rough order: **~4–6 focused days** for annotation + DTOs across 20 resources, plus **~1–2 days**
  for the seed/operationId/parity-tooling plumbing, plus review. Call it **~1.5 developer-weeks**,
  most of it the same schema-authoring labor the hand spec already embodies, just relocated.

## Recommendation: KEEP-STATIC (deliberately), do NOT adopt in v3.6

The spike proves generation is *feasible* and keeps the WAR clean (`provided` scope,
byte-identical `WEB-INF/lib`). But it does not clear the bar for replacing the hand-authored spec:

- **No free lunch on the expensive part.** The resources have no typed response models, so every
  schema must still be hand-authored — as `@Schema` DTOs instead of JSON. The dominant cost
  (documenting shapes) is unchanged; only its location moves, while adding a build-time plugin, a
  new dependency, and annotation noise across 20 resources.
- **Net fidelity is a wash or worse today.** Against the served spec, generation *loses*
  operationId stability, global security schemes, and `info`; *gains* descriptions, error
  responses, and typed schemas; and *introduces* two defects (duplicate path params, spurious
  `app_key`) that need per-site fixes. The parity guard (`check-openapi-parity.mjs`), which today
  cheaply keeps the static file honest, would have to be rebuilt.
- **The static spec + parity script already solves the actual problem** (issue #15): the spec
  cannot silently drift from the code, because CI fails on an undocumented endpoint/param. That is
  the guarantee generation would replace — at higher structural cost and lower top-matter fidelity.

Revisit only if/when resources migrate to typed response models (which would make schema generation
genuinely free), or if a client-SDK-generation need makes machine-authored schemas worth the churn.

## Draft ADR

**Title:** Keep the hand-authored OpenAPI spec; do not adopt build-time annotation generation

**Status:** proposed

**Context:** `/apidoc` serves a hand-authored `openapi.json` kept honest by
`scripts/check-openapi-parity.mjs` (fails CI on any undocumented endpoint/parameter), with no
runtime swagger scanner in the WAR (issue #15). A v3.5 spike evaluated whether
`swagger-maven-plugin-jakarta` could generate the spec from `io.swagger.core.v3` annotations,
annotating `TagResource` (8 ops) end-to-end with `provided`-scope annotations.

**Decision:** Retain the hand-authored spec + parity-script model. Do not introduce build-time
OpenAPI generation in v3.6.

**Consequences:**
- *Positive:* zero new build dependency/plugin; WAR `WEB-INF/lib` stays byte-identical; the cheap,
  proven parity guard continues to prevent drift; no annotation noise across 20 resources; global
  security schemes and `info` metadata stay first-class.
- *Negative / accepted:* API shapes remain documented by hand (in JSON, not Java); no
  auto-generated client SDKs; contributors update `openapi.json` alongside code (the parity script
  enforces this).
- *Reversible:* the spike proved feasibility and the `provided`-scope recipe. If resources later
  gain typed response models — making schema generation actually free — or a client-SDK need
  arises, revisit with the plumbing (`@OpenAPIDefinition` seed, explicit operationIds, `app_key`
  suppression, duplicate-path-param fix, parity-tool rework) already scoped in this document.
