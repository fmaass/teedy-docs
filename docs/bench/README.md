# Search benchmark harness

A reproducible, seeded measurement of `GET /api/document/list?search=…` — the harness behind the
#290 search work. It exists because a lorem corpus does not reproduce the defect: the cost lives in
the size of the `content` term dictionary and in the number of matched document ids, not in the
document count.

## Corpus

`corpus.py` generates ~3.4 kB of German-compound prose per document, deterministic in the document
index, giving **244 001 distinct `content` terms over 3 000 documents** — an OCR-shaped dictionary.
Probe terms are planted at fixed frequencies:

| term | role | documents |
|---|---|---|
| `werkstattwagen` | rare exact | 5 seeded (103 hits, see below) |
| `schlüssel` | high frequency | 1 200 (40 %) |
| `werkstattwogen` | typo, error at index 10 | — matches the fuzzy neighbourhood |
| `warkstattwagen` | typo inside the first two characters | — semantics probe, 0 hits since v3.8.6 |
| `der` | below the fuzzy length gate | 2 203 — no fuzzy arm, so this probe times the SQL phase |

`rare=103` rather than 5 because Lucene's `FuzzyQuery` rewrite keeps the top 50 expansions; that
caps the result set, not the automaton's dictionary walk.

## Running it

```bash
# 1. a disposable instance (NEVER production: the seeder logs in as admin/admin)
docker run -d --name teedy-bench-db --network bench-net \
  -e POSTGRES_DB=teedy -e POSTGRES_USER=teedy -e POSTGRES_PASSWORD=teedy postgres:17-alpine
docker run -d --name teedy-bench-app --network bench-net -p 18093:8080 \
  -e DATABASE_URL=jdbc:postgresql://teedy-bench-db:5432/teedy \
  -e DATABASE_USER=teedy -e DATABASE_PASSWORD=teedy -v teedy-bench-data:/data <image>

# 2. seed, then WAIT for indexing to finish — a probe against a half-indexed corpus measures nothing
python3 seed.py --base http://localhost:18093 --docs 3000 --workers 8
until [ "$(./quiesce.sh http://localhost:18093)" = "docs=3000 rare=103 common=1216" ]; do sleep 20; done

# 3. probe: 1 discarded warm-up + 5 measured runs per probe, median and max reported
./probe.sh http://localhost:18093 LABEL

# 4. result-set + ordering equality across a change: capture on both builds and diff
python3 idlist.py --base http://localhost:18093 --search der > der-before.txt
```

Mount `/data` as a **named volume**, not a host directory — the image runs as a non-root user and
cannot create `/data/log` under a fresh host bind mount.

Compare `bytes=` between two runs: an unequal payload invalidates the comparison.

## The database engine is the dominant variable

Measured 2026-08-24 on one host, one binary (built from `e34a642b`), one corpus, differing only in
`DATABASE_URL` — set (PostgreSQL 17.11) or unset (embedded H2, the fallback `EMF` warns is "only
suitable for testing purpose"). Median of 5 runs, `limit=20`:

| probe | hits | embedded H2 | PostgreSQL 17 |
|---|---|---|---|
| `der` | 2 203 | **2.374 s** | **0.098 s** |
| `schlüssel` | 1 216 | 1.002 s | 0.377 s |
| `werkstattwagen` | 103 | 0.477 s | 0.458 s |
| `werkstattwogen` | 103 | 0.470 s | 0.473 s |
| `warkstattwagen` | 0 | 0.012 s | 0.018 s |

The `der` probe carries no fuzzy arm, so what separates 2.374 s from 0.098 s is the SQL phase over
the matched id set — the whole set is injected as `d.DOC_ID_C in :documentIdList`. On H2 that phase
costs ~1 ms per matched id and grows faster than linearly (103 hits 0.049 s · 1 216 hits 0.70 s ·
2 203 hits 2.32 s, all at `limit=1`); on PostgreSQL it is flat and ~30× cheaper. Page size barely
moves the H2 number (`der` at `limit=1` 2.32 s vs `limit=100` 2.42 s), which places the cost in the
id-set phase and not in assembling the page.

On PostgreSQL the same request spends ~35 ms in SQL over ~180 statements (`log_min_duration_statement=0`,
one request): the largest single statement is the `select count(*) … from (…)` wrapper at 6–10 ms,
and the per-document tag query costs ~0.9 ms per returned row (`der` at `limit=1` 0.078 s vs
`limit=100` 0.170 s).

Note when interpreting either column: with no `DATABASE_URL`, `EMF` configures H2 with
`org.hibernate.dialect.HSQLDialect`, while the H2 CI job configures `H2Dialect` — the two do not
exercise the same generated SQL.
