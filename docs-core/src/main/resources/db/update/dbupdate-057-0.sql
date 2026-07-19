-- #119 content-hash duplicate detection. FIL_CONTENTMAC_C stores a per-document KEYED MAC of a file's
-- plaintext (HMAC-SHA-256 under a key derived from a deploy-time master secret + the document id), NOT a
-- raw hash — the master secret never lives in T_CONFIG or the DB, and an absent OR TOO-WEAK secret degrades
-- the whole feature to OFF (the column simply stays NULL, uploads unchanged). The master secret is provided
-- at deploy time via the DOCS_DEDUP_MASTER_KEY env var (or DOCS_DEDUP_MASTER_KEY_FILE mounted-secret path)
-- and MUST supply >= 32 bytes (256 bits) of key material as hex or base64 — generate with
-- `openssl rand -hex 32`. A shorter/malformed value keeps the feature OFF (never silently enabled-but-weak).
-- Nullable and additive so an old install upgrades with no behaviour change and no backfill obligation. Dual-engine safe: ADD COLUMN IF NOT EXISTS
-- and CREATE INDEX IF NOT EXISTS apply on both H2 and PostgreSQL, and varchar(64) is unchanged by the
-- H2->Postgres dialect transform. On H2 (auto-commit DDL) IF NOT EXISTS makes a re-run after a partial
-- failure skip the already-created objects; on PostgreSQL (transactional DDL) a failed run rolls back cleanly.
alter table T_FILE add column if not exists FIL_CONTENTMAC_C varchar(64);
-- NON-UNIQUE by design: a renamed-identical upload and a legitimate keep-both both repeat a (document, mac)
-- pair, so uniqueness would wrongly reject them. The index only accelerates the read-only (document, mac)
-- duplicate-hint lookup and the null-mac backfill scan.
create index if not exists IDX_FIL_DOC_MAC_C on T_FILE (FIL_IDDOC_C, FIL_CONTENTMAC_C);
update T_CONFIG set CFG_VALUE_C = '57' where CFG_ID_C = 'DB_VERSION';
