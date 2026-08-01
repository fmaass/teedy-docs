#!/usr/bin/env bash
# Test runner for scripts/repair-mojibake.mjs.
#
# The value-level tests always run. The database-backed --execute contract tests are opt-in,
# because they need a PostgreSQL they are allowed to write to:
#
#   MOJIBAKE_TEST_PG=1 PGHOST=127.0.0.1 PGPORT=5432 PGUSER=... PGDATABASE=<throwaway> \
#     scripts/test-repair-mojibake.sh
#
# Point them at a THROWAWAY database only. They create and drop a table named zz_mojibake_test.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec node --test "$script_dir/repair-mojibake.test.mjs"
