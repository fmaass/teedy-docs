#!/usr/bin/env bash
# probe.sh <base-url> <label>   -- 1 warm-up + 5 measured runs per probe.
set -euo pipefail
BASE="$1"; LABEL="$2"
CK="$(curl -s -X POST -d 'username=admin&password=admin&remember=true' "$BASE/api/user/login" -c - | awk '/auth_token/{print $7}')"
[ -n "$CK" ] || { echo "login failed"; exit 1; }
OUT=$(mktemp)

hit () { # $1 = search term ; writes body to $OUT, prints time_total
  curl -s -o "$OUT" -w '%{time_total}' -G \
    --cookie "auth_token=$CK" \
    --data-urlencode "search=$1" \
    --data 'limit=20&offset=0&sort_column=3&asc=false' \
    "$BASE/api/document/list"
}

probe () { # $1 = probe name, $2 = term
  hit "$2" >/dev/null                       # warm-up, discarded
  local times=() t
  for n in 1 2 3 4 5; do t=$(hit "$2"); times+=("$t"); done
  local total bytes
  total=$(python3 -c 'import sys,json;print(json.load(open(sys.argv[1])).get("total"))' "$OUT")
  bytes=$(stat -c%s "$OUT")
  python3 - "$LABEL" "$1" "$2" "$total" "$bytes" "${times[@]}" <<'PY'
import sys
label, name, term, total, byt = sys.argv[1:6]
ts = sorted(float(x) for x in sys.argv[6:])
med = ts[len(ts)//2] if len(ts) % 2 else (ts[len(ts)//2-1]+ts[len(ts)//2])/2
print("%s|%s|%s|total=%s|bytes=%s|median=%.3f|max=%.3f|runs=%s" %
      (label, name, term, total, byt, med, ts[-1], ",".join("%.3f" % t for t in ts)))
PY
}

probe rare-exact   "werkstattwagen"
probe common       "schlüssel"
probe typo-after2  "werkstattwogen"
probe typo-first2  "warkstattwagen"
# The SQL floor (TEEDY-118 / #290 follow-up): a 3-character term is below the fuzzy
# length gate, so no fuzzy arm is added and the Lucene phase is trivial - what this probe
# times is the SQL phase over the whole matched id set.
probe sql-floor    "der"
rm -f "$OUT"
